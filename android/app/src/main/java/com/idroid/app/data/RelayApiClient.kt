package com.idroid.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Starts the trailer the Mac appends after a command's output: {"exit":N,"cwd":"..."}. */
private const val TRAILER_MARK = "\n\u001E"

class RelayException(message: String, val httpCode: Int? = null) : IOException(message)

data class DownloadResult(val contentType: String, val filename: String?)

/**
 * Talks to the relay API described in relay/README.md. One instance is
 * shared app-wide (see IdroidApplication); it's stateless beyond the
 * underlying connection pool.
 */
class RelayApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Keeps the websocket connection alive through NATs/idle timeouts;
        // comfortably under the relay's 60s read deadline (server.go).
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Begins a pairing session on [relayBaseUrl]. Pass [token] when this phone
     * is already paired, so the other device joins its group under the same
     * identity instead of a new one being minted.
     */
    suspend fun startPairing(relayBaseUrl: String, name: String, token: String? = null): PairStartResponse {
        val body = """{"name":"${jsonEscape(name)}"}""".toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/start").post(body).withToken(token).build()
        val responseBody = executeForString(request)
        return json.decodeFromString(responseBody)
    }

    /** Claims a pairing code shown by another device's [startPairing] call. */
    suspend fun claimPairing(relayBaseUrl: String, code: String, name: String, token: String? = null): PairClaimResponse {
        val body = """{"code":"${jsonEscape(code)}","name":"${jsonEscape(name)}"}"""
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/claim").post(body).withToken(token).build()
        val responseBody = executeForString(request)
        return json.decodeFromString(responseBody)
    }

    /** Polled by the device that called [startPairing] to learn when the other side has claimed the code. */
    suspend fun pollPairingStatus(relayBaseUrl: String, sessionId: String): PairStatusResponse {
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/status/$sessionId").get().build()
        val responseBody = executeForString(request)
        return json.decodeFromString(responseBody)
    }

    private fun trimmed(relayBaseUrl: String) = relayBaseUrl.trimEnd('/')

    private fun Request.Builder.withToken(token: String?) = apply {
        if (token != null) header("Authorization", "Bearer $token")
    }

    /** This phone plus every other device in its group. */
    suspend fun listDevices(config: PairingConfig): DevicesResponse {
        val request = authedRequest(config, "/devices").get().build()
        val body = executeForString(request)
        return json.decodeFromString(body)
    }

    /** Unpairs a device in this phone's group (or this phone itself). */
    suspend fun removeDevice(config: PairingConfig, deviceId: String) {
        val request = authedRequest(config, "/devices/$deviceId").delete().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw RelayException("remove failed", response.code)
        }
    }

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    suspend fun listInbox(config: PairingConfig): List<ItemMeta> {
        val request = authedRequest(config, "/inbox").get().build()
        val body = executeForString(request)
        return json.decodeFromString(body)
    }

    suspend fun deleteItem(config: PairingConfig, itemId: String) {
        val request = authedRequest(config, "/inbox/$itemId").delete().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw RelayException("delete failed", response.code)
        }
    }

    /** Streams an item's payload straight to [output] without buffering it in memory. */
    suspend fun downloadItemTo(config: PairingConfig, itemId: String, output: OutputStream): DownloadResult =
        withContext(Dispatchers.IO) {
            val request = authedRequest(config, "/inbox/$itemId/content").get().build()
            execute(request).use { response ->
                if (!response.isSuccessful) throw RelayException("download failed", response.code)
                val responseBody = response.body ?: throw RelayException("empty response body")
                responseBody.byteStream().use { it.copyTo(output) }
                DownloadResult(
                    contentType = response.header("Content-Type") ?: "application/octet-stream",
                    filename = parseFilename(response.header("Content-Disposition")),
                )
            }
        }

    /** Downloads a clipboard item straight into memory — bounded by MAX_CLIPBOARD_BYTES on the sending side. */
    suspend fun downloadBytes(config: PairingConfig, itemId: String): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        downloadItemTo(config, itemId, buffer)
        return buffer.toByteArray()
    }

    suspend fun uploadText(config: PairingConfig, recipientId: String, kind: String, text: String): ItemMeta =
        uploadBytes(config, recipientId, kind, text.toByteArray(Charsets.UTF_8), "shared-text.txt", "text/plain")

    /** In-memory upload for small payloads (text, clipboard images); files go through [uploadUri]. */
    suspend fun uploadBytes(
        config: PairingConfig,
        recipientId: String,
        kind: String,
        bytes: ByteArray,
        filename: String,
        contentType: String,
    ): ItemMeta {
        val body = bytes.toRequestBody(contentType.toMediaTypeOrNull())
        return upload(config, recipientId, kind = kind, filename = filename, contentType = contentType, payload = body)
    }

    suspend fun uploadUri(context: Context, config: PairingConfig, recipientId: String, uri: Uri, mimeType: String?): ItemMeta {
        val filename = queryDisplayName(context, uri) ?: "shared-file"
        val contentType = mimeType ?: "application/octet-stream"
        val body = ContentUriRequestBody(context, uri, contentType.toMediaTypeOrNull())
        return upload(config, recipientId, kind = ItemKind.FILE, filename = filename, contentType = contentType, payload = body)
    }

    private suspend fun upload(
        config: PairingConfig,
        recipientId: String,
        kind: String,
        filename: String,
        contentType: String,
        payload: RequestBody,
    ): ItemMeta {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("recipient", recipientId)
            .addFormDataPart("kind", kind)
            .addFormDataPart("filename", filename)
            .addFormDataPart("content_type", contentType)
            .addFormDataPart("payload", filename, payload)
            .build()

        val request = authedRequest(config, "/upload").post(multipart).build()
        val body = executeForString(request)
        return json.decodeFromString(body)
    }

    // --- Live file browsing (ADR-0004) ---

    /** Lists a folder shared by another device; "/" lists its shared folders. */
    suspend fun listRemote(config: PairingConfig, deviceId: String, path: String): List<FsEntry> {
        val request = fsRequest(config, deviceId, "list", path).get().build()
        val body = executeForString(request)
        return json.decodeFromString<FsListing>(body).entries
    }

    /**
     * Streams a file from another device's shared folder straight to [output].
     * [onProgress] gets the fraction done (0..1) when the size is known; the
     * copy stops between chunks once the calling coroutine is cancelled.
     */
    suspend fun downloadRemoteTo(
        config: PairingConfig,
        deviceId: String,
        path: String,
        output: OutputStream,
        onProgress: ((Float) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val request = fsRequest(config, deviceId, "content", path).get().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw RelayException("download failed", response.code)
            val responseBody = response.body ?: throw RelayException("empty response body")
            val total = responseBody.contentLength()
            responseBody.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (total > 0) onProgress?.invoke(copied.toFloat() / total)
                }
            }
        }
    }

    /**
     * Opens the body of a file another device is uploading into one of our
     * shared folders (fs_request op "upload"); the caller reads and closes it.
     */
    suspend fun pullFsUpload(config: PairingConfig, requestId: String): Response {
        val response = execute(authedRequest(config, "/fs/pull/$requestId").get().build())
        if (!response.isSuccessful) {
            response.close()
            throw RelayException("pull failed", response.code)
        }
        return response
    }

    /** Answers an fs_request; [body] carries its own Content-Type and length. */
    suspend fun respondFs(config: PairingConfig, requestId: String, status: Int, body: RequestBody) {
        val request = authedRequest(config, "/fs/respond/$requestId")
            .header("X-Idroid-Status", status.toString())
            .post(body)
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw RelayException("respond failed", response.code)
        }
    }

    private fun fsRequest(config: PairingConfig, deviceId: String, op: String, path: String): Request.Builder {
        val url = (config.relayBaseUrl.trimEnd('/') + "/fs/$deviceId/$op").toHttpUrl()
            .newBuilder()
            .addQueryParameter("path", path)
            .build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${config.deviceToken}")
    }

    // --- Remote commands (ADR-0005) ---

    /** What the Mac lets this phone run: named actions, and whether the free terminal is on. */
    suspend fun commandCatalog(config: PairingConfig, deviceId: String): CommandCatalog {
        val body = executeForString(fsRequest(config, deviceId, "commands", "/").get().build())
        return json.decodeFromString(body)
    }

    /** Commands or paths completing the last word of [line] (terminal must be on). */
    suspend fun completeCommand(config: PairingConfig, deviceId: String, line: String, cwd: String?): List<String> {
        val payload = json.encodeToString(CompleteRequest.serializer(), CompleteRequest(line, cwd))
        val request = fsRequest(config, deviceId, "complete", "/")
            .put(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return json.decodeFromString<CompleteResponse>(executeForString(request)).suggestions
    }

    /**
     * Runs a command on the Mac, handing output to [onOutput] as it arrives.
     * Cancelling the coroutine closes the connection, which makes the Mac stop
     * the command. Returns the exit code and the folder the shell ended in.
     */
    suspend fun execCommand(
        config: PairingConfig,
        deviceId: String,
        exec: ExecRequest,
        onOutput: (String) -> Unit,
    ): ExecResult = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(ExecRequest.serializer(), exec)
        val request = fsRequest(config, deviceId, "exec", "/")
            .put(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val call = streamingClient.newCall(request)
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancel = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = response.body?.string()?.trim().orEmpty()
                    throw RelayException(message.ifEmpty { "command failed (${response.code})" }, response.code)
                }
                val reader = (response.body ?: throw RelayException("empty response body")).charStream()
                val buffer = CharArray(8 * 1024)
                val tail = StringBuilder()
                while (true) {
                    ensureActive()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    tail.append(buffer, 0, n)
                    // Hold back from a record separator on: it may be the trailer.
                    val sep = tail.indexOf(TRAILER_MARK)
                    val emitUpTo = if (sep >= 0) sep else tail.length
                    if (emitUpTo > 0) {
                        onOutput(tail.substring(0, emitUpTo))
                        tail.delete(0, emitUpTo)
                    }
                }
                val trailer = tail.toString().removePrefix(TRAILER_MARK).trim()
                runCatching { json.decodeFromString<ExecResult>(trailer) }
                    .getOrElse { if (trailer.isNotEmpty()) onOutput(trailer); ExecResult(exit = -1, cwd = exec.cwd ?: "") }
            }
        } finally {
            cancel?.dispose()
        }
    }

    /** Commands can run for minutes and stay quiet: no read deadline. */
    private val streamingClient by lazy { httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() }

    fun openWebSocket(config: PairingConfig, listener: WebSocketListener): WebSocket {
        val wsUrl = toWebSocketUrl(config.relayBaseUrl) + "/ws?token=" + config.deviceToken
        val request = Request.Builder().url(wsUrl).build()
        return httpClient.newWebSocket(request, listener)
    }

    /** Live mouse/keyboard pipe to [peerId] (ADR-0008). */
    fun openInputSocket(config: PairingConfig, peerId: String, listener: WebSocketListener): WebSocket {
        val wsUrl = toWebSocketUrl(config.relayBaseUrl) + "/input/" + peerId + "?token=" + config.deviceToken
        return streamingClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
            .newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun authedRequest(config: PairingConfig, path: String): Request.Builder =
        Request.Builder()
            .url(config.relayBaseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${config.deviceToken}")

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        httpClient.newCall(request).executeOrThrow()
    }

    /**
     * Reads the body on the IO dispatcher too: execute() returns once the
     * headers arrive, and reading the rest on the main thread throws
     * NetworkOnMainThreadException whenever it hasn't all arrived yet.
     */
    private suspend fun executeForString(request: Request): String = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).executeOrThrow().use { it.bodyStringOrThrow() }
        } catch (e: IOException) {
            if (e is RelayException) throw e
            throw RelayException("network error: ${e.message}")
        }
    }

    private fun Call.executeOrThrow(): Response =
        try {
            execute()
        } catch (e: IOException) {
            throw RelayException("network error: ${e.message}")
        }

    private fun Response.bodyStringOrThrow(): String {
        if (!isSuccessful) throw RelayException("request failed", code)
        return body?.string() ?: throw RelayException("empty response body")
    }

    private fun toWebSocketUrl(httpUrl: String): String =
        httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/')

    private fun parseFilename(contentDisposition: String?): String? {
        contentDisposition ?: return null
        val match = Regex("filename=\"(.*)\"").find(contentDisposition) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                return it.getString(nameIndex)
            }
        }
        return null
    }
}

/**
 * Streams a content:// Uri's bytes directly into the multipart request body
 * instead of buffering the whole file in memory — important since shared
 * files can be large photos/videos.
 */
internal class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mediaType: MediaType?,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        cursor?.use {
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && it.moveToFirst() && !it.isNull(sizeIndex)) {
                return it.getLong(sizeIndex)
            }
        }
        return -1L
    }

    override fun writeTo(sink: BufferedSink) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open $uri")
        stream.use { input ->
            sink.writeAll(input.source())
        }
    }
}

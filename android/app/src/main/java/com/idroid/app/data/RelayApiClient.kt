package com.idroid.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
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

    /** Begins a pairing session on [relayBaseUrl]; no device token exists yet, so this is unauthenticated. */
    suspend fun startPairing(relayBaseUrl: String, name: String): PairStartResponse {
        val body = """{"name":"${jsonEscape(name)}"}""".toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/start").post(body).build()
        val responseBody = execute(request).use { it.bodyStringOrThrow() }
        return json.decodeFromString(responseBody)
    }

    /** Claims a pairing code shown by another device's [startPairing] call. */
    suspend fun claimPairing(relayBaseUrl: String, code: String, name: String): PairClaimResponse {
        val body = """{"code":"${jsonEscape(code)}","name":"${jsonEscape(name)}"}"""
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/claim").post(body).build()
        val responseBody = execute(request).use { it.bodyStringOrThrow() }
        return json.decodeFromString(responseBody)
    }

    /** Polled by the device that called [startPairing] to learn when the other side has claimed the code. */
    suspend fun pollPairingStatus(relayBaseUrl: String, sessionId: String): PairStatusResponse {
        val request = Request.Builder().url(trimmed(relayBaseUrl) + "/pair/status/$sessionId").get().build()
        val responseBody = execute(request).use { it.bodyStringOrThrow() }
        return json.decodeFromString(responseBody)
    }

    private fun trimmed(relayBaseUrl: String) = relayBaseUrl.trimEnd('/')

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    suspend fun listInbox(config: PairingConfig): List<ItemMeta> {
        val request = authedRequest(config, "/inbox").get().build()
        val body = execute(request).use { it.bodyStringOrThrow() }
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

    /** Downloads a text/clipboard item straight into memory — small by construction (clipboard payloads). */
    suspend fun downloadText(config: PairingConfig, itemId: String): String {
        val buffer = java.io.ByteArrayOutputStream()
        downloadItemTo(config, itemId, buffer)
        return buffer.toString("UTF-8")
    }

    suspend fun uploadText(config: PairingConfig, kind: String, text: String): ItemMeta {
        val body = text.toRequestBody("text/plain".toMediaTypeOrNull())
        return upload(config, kind = kind, filename = "shared-text.txt", contentType = "text/plain", payload = body)
    }

    suspend fun uploadUri(context: Context, config: PairingConfig, uri: Uri, mimeType: String?): ItemMeta {
        val filename = queryDisplayName(context, uri) ?: "shared-file"
        val contentType = mimeType ?: "application/octet-stream"
        val body = ContentUriRequestBody(context, uri, contentType.toMediaTypeOrNull())
        return upload(config, kind = ItemKind.FILE, filename = filename, contentType = contentType, payload = body)
    }

    private suspend fun upload(
        config: PairingConfig,
        kind: String,
        filename: String,
        contentType: String,
        payload: RequestBody,
    ): ItemMeta {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("recipient", config.recipientDeviceId)
            .addFormDataPart("kind", kind)
            .addFormDataPart("filename", filename)
            .addFormDataPart("content_type", contentType)
            .addFormDataPart("payload", filename, payload)
            .build()

        val request = authedRequest(config, "/upload").post(multipart).build()
        val body = execute(request).use { it.bodyStringOrThrow() }
        return json.decodeFromString(body)
    }

    fun openWebSocket(config: PairingConfig, listener: WebSocketListener): WebSocket {
        val wsUrl = toWebSocketUrl(config.relayBaseUrl) + "/ws?token=" + config.deviceToken
        val request = Request.Builder().url(wsUrl).build()
        return httpClient.newWebSocket(request, listener)
    }

    private fun authedRequest(config: PairingConfig, path: String): Request.Builder =
        Request.Builder()
            .url(config.relayBaseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${config.deviceToken}")

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        httpClient.newCall(request).executeOrThrow()
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
private class ContentUriRequestBody(
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

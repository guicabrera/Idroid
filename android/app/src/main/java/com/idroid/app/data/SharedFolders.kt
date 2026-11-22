package com.idroid.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A folder on this phone that paired devices may browse (ADR-0004), shown to
 * them under [name]. [writable] when we hold a write grant too, so files can
 * be dropped into it from the Mac.
 */
data class SharedFolder(val name: String, val treeUri: Uri, val writable: Boolean)

/**
 * The folders this phone shares, as Storage Access Framework trees picked
 * with ACTION_OPEN_DOCUMENT_TREE. Folders shared before uploads existed only
 * kept the read grant; picking them again adds the write grant.
 */
class SharedFoldersRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("idroid_settings", Context.MODE_PRIVATE)

    /** Folders whose grant the user has since revoked are dropped. */
    fun load(): List<SharedFolder> {
        val permissions = context.contentResolver.persistedUriPermissions
        val granted = permissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        val writable = permissions.filter { it.isWritePermission }.map { it.uri }.toSet()
        val uris = uris().filter { it in granted }
        return named(uris, writable)
    }

    fun add(treeUri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, readWrite) }
            .onFailure { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val uris = uris()
        if (treeUri !in uris) save(uris + treeUri)
    }

    /** [keepGrant] is true when the same folder is still the save location and needs its grant. */
    fun remove(folder: SharedFolder, keepGrant: Boolean) {
        save(uris() - folder.treeUri)
        if (!keepGrant) {
            val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(folder.treeUri, readWrite) }
                .onFailure {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(folder.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
        }
    }

    fun isShared(uri: Uri): Boolean = uri in uris()

    private fun uris(): List<Uri> =
        prefs.getString(KEY_URIS, null)?.split('\n')?.filter { it.isNotBlank() }?.map(Uri::parse) ?: emptyList()

    private fun save(uris: List<Uri>) {
        prefs.edit().putString(KEY_URIS, uris.joinToString("\n")).apply()
    }

    /** Folder names, suffixed " 2", " 3"… on clashes so each is one unambiguous path segment. */
    private fun named(uris: List<Uri>, writable: Set<Uri>): List<SharedFolder> {
        val used = mutableSetOf<String>()
        return uris.map { uri ->
            val base = displayName(uri)
            var name = base
            var n = 2
            while (name in used) name = "$base ${n++}"
            used += name
            SharedFolder(name, uri, uri in writable)
        }
    }

    private fun displayName(treeUri: Uri): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val name = runCatching {
            context.contentResolver.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() && '/' !in it } ?: "Phone"
    }

    private companion object {
        const val KEY_URIS = "shared_folder_uris"
    }
}

/**
 * Answers other devices' requests to browse this phone's shared folders
 * (ADR-0004). The relay forwards each one over the websocket as an
 * "fs_request" (see RelaySyncService) and pipes our POST /fs/respond back to
 * the caller, so the phone never accepts inbound connections.
 */
class FileShareResponder(
    private val context: Context,
    private val repository: SharedFoldersRepository,
    private val apiClient: RelayApiClient,
) {

    private val json = Json { encodeDefaults = true }
    private val folderIds = ConcurrentHashMap<String, CachedFolder>()

    suspend fun handle(config: PairingConfig, event: NotifyEvent) = withContext(Dispatchers.IO) {
        val requestId = event.request_id.ifBlank { return@withContext }
        try {
            respond(config, requestId, event.op, event.path)
        } catch (e: FsException) {
            respondText(config, requestId, e.status, e.message ?: "error")
        } catch (e: Exception) {
            runCatching { respondText(config, requestId, 500, e.message ?: "error") }
        }
    }

    private suspend fun respond(config: PairingConfig, requestId: String, op: String, path: String) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) throw FsException(400, "invalid path")
        val folders = repository.load()

        if (op == "upload" && segments.size < 2) throw FsException(400, "open a shared folder to drop files into it")
        if (segments.isEmpty()) {
            if (op != "list") throw FsException(400, "not a file")
            respondListing(config, requestId, folders.map { FsEntry(name = it.name, path = "/${it.name}", is_dir = true) })
            return
        }

        val folder = folders.firstOrNull { it.name == segments[0] } ?: throw FsException(404, "not found")
        val virtualPath = "/" + segments.joinToString("/")
        when (op) {
            "list" -> {
                val entries = uncachedOnFailure {
                    val doc = resolve(folder, segments)
                    if (!doc.isDir) throw FsException(400, "not a folder")
                    children(folder, segments, doc.id)
                }
                    .filterNot { it.name.startsWith(".") }
                    .sortedWith(compareBy<Doc>({ !it.isDir }, { it.name.lowercase() }))
                    .map { it.toEntry("$virtualPath/${it.name}") }
                respondListing(config, requestId, entries)
            }
            "content" -> {
                val doc = uncachedOnFailure { resolve(folder, segments) }
                if (doc.isDir) throw FsException(400, "is a folder")
                val uri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, doc.id)
                apiClient.respondFs(config, requestId, 200, ContentUriRequestBody(context, uri, doc.mime.toMediaTypeOrNull()))
            }
            "upload" -> receiveUpload(config, requestId, folder, segments)
            else -> throw FsException(400, "unknown op")
        }
    }

    /**
     * Writes a file another device dropped into one of our folders: the last
     * segment is its name (the provider adds " (1)" if it's taken), the rest
     * the folder. The bytes are pulled from the relay as the sender uploads.
     */
    private suspend fun receiveUpload(config: PairingConfig, requestId: String, folder: SharedFolder, segments: List<String>) {
        if (!folder.writable) {
            throw FsException(403, "${folder.name} is read-only. On the phone, open Idroid → Shared folders → Share a folder and pick it again to allow this.")
        }
        val name = segments.last()
        val parentSegments = segments.dropLast(1)
        val parent = uncachedOnFailure { resolve(folder, parentSegments) }
        if (!parent.isDir) throw FsException(400, "not a folder")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, parent.id)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

        val created = apiClient.pullFsUpload(config, requestId).use { response ->
            val target = DocumentsContract.createDocument(context.contentResolver, parentUri, mime, name)
                ?: throw FsException(500, "could not create $name")
            try {
                val output = context.contentResolver.openOutputStream(target) ?: throw FsException(500, "could not write $name")
                output.use { out -> response.body!!.byteStream().use { it.copyTo(out) } }
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                throw e
            }
            target
        }

        val savedName = context.contentResolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        val entry = FsEntry(name = savedName, path = "/" + (parentSegments + savedName).joinToString("/"), is_dir = false, mime = mime)
        val body = json.encodeToString(entry).toRequestBody("application/json".toMediaTypeOrNull())
        apiClient.respondFs(config, requestId, 201, body)
    }

    /**
     * Walks [segments] down from the shared folder. SAF can only list a
     * folder's children, not look one up by name, so each level costs a full
     * listing; folders seen before come from [folderIds] instead.
     */
    private fun resolve(folder: SharedFolder, segments: List<String>): Doc {
        var doc = Doc(DocumentsContract.getTreeDocumentId(folder.treeUri), segments[0], Document.MIME_TYPE_DIR, 0, null)
        for (i in 1 until segments.size) {
            if (!doc.isDir) throw FsException(404, "not found")
            val prefix = segments.subList(0, i + 1)
            val cached = folderIds[cacheKey(folder, prefix)]?.takeIf { it.storedAt > System.currentTimeMillis() - CACHE_TTL_MS }
            doc = if (cached != null) {
                Doc(cached.documentId, segments[i], Document.MIME_TYPE_DIR, 0, null)
            } else {
                children(folder, segments.subList(0, i), doc.id).firstOrNull { it.name == segments[i] }
                    ?: throw FsException(404, "not found")
            }
        }
        return doc
    }

    /** A cached folder id can go stale (renamed, deleted); retry once from scratch before failing. */
    private fun <T> uncachedOnFailure(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (e is FsException && e.status != 404) throw e
            folderIds.clear()
            block()
        }

    private fun cacheKey(folder: SharedFolder, segments: List<String>) = "${folder.treeUri}\n${segments.joinToString("/")}"

    /** Lists a folder and remembers its subfolders' ids under [parentSegments]. */
    private fun children(folder: SharedFolder, parentSegments: List<String>, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.treeUri, parentId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val docs = mutableListOf<Doc>()
        val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: throw FsException(404, "not found")
        cursor.use { c ->
            while (c.moveToNext()) {
                docs += Doc(
                    id = c.getString(0),
                    name = c.getString(1) ?: continue,
                    mime = c.getString(2) ?: "application/octet-stream",
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) null else c.getLong(4),
                )
            }
        }
        if (folderIds.size > CACHE_LIMIT) folderIds.clear()
        val now = System.currentTimeMillis()
        for (doc in docs) {
            if (doc.isDir) folderIds[cacheKey(folder, parentSegments + doc.name)] = CachedFolder(doc.id, now)
        }
        return docs
    }

    private suspend fun respondListing(config: PairingConfig, requestId: String, entries: List<FsEntry>) {
        val body = json.encodeToString(FsListing(entries)).toRequestBody("application/json".toMediaTypeOrNull())
        apiClient.respondFs(config, requestId, 200, body)
    }

    private suspend fun respondText(config: PairingConfig, requestId: String, status: Int, message: String) {
        apiClient.respondFs(config, requestId, status, message.toRequestBody("text/plain".toMediaTypeOrNull()))
    }

    private data class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long?) {
        val isDir get() = mime == Document.MIME_TYPE_DIR

        fun toEntry(path: String) = FsEntry(
            name = name,
            path = path,
            is_dir = isDir,
            size = if (isDir) 0 else size,
            modified = modified?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
            mime = if (isDir) null else mime,
        )
    }

    private data class CachedFolder(val documentId: String, val storedAt: Long)

    private class FsException(val status: Int, message: String) : Exception(message)

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val CACHE_LIMIT = 5_000
    }
}

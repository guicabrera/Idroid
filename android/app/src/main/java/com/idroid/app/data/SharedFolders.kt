package com.idroid.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** A folder on this phone that paired devices may browse (ADR-0004), shown to them under [name]. */
data class SharedFolder(val name: String, val treeUri: Uri)

/**
 * The folders this phone shares, as Storage Access Framework trees picked
 * with ACTION_OPEN_DOCUMENT_TREE. Read-only: only the read grant is kept.
 */
class SharedFoldersRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("idroid_settings", Context.MODE_PRIVATE)

    /** Folders whose grant the user has since revoked are dropped. */
    fun load(): List<SharedFolder> {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        val uris = uris().filter { it in granted }
        return named(uris)
    }

    fun add(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uris = uris()
        if (treeUri !in uris) save(uris + treeUri)
    }

    /** [keepGrant] is true when the same folder is still the save location and needs its grant. */
    fun remove(folder: SharedFolder, keepGrant: Boolean) {
        save(uris() - folder.treeUri)
        if (!keepGrant) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(folder.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    private fun named(uris: List<Uri>): List<SharedFolder> {
        val used = mutableSetOf<String>()
        return uris.map { uri ->
            val base = displayName(uri)
            var name = base
            var n = 2
            while (name in used) name = "$base ${n++}"
            used += name
            SharedFolder(name, uri)
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

        if (segments.isEmpty()) {
            if (op != "list") throw FsException(400, "not a file")
            respondListing(config, requestId, folders.map { FsEntry(name = it.name, path = "/${it.name}", is_dir = true) })
            return
        }

        val folder = folders.firstOrNull { it.name == segments[0] } ?: throw FsException(404, "not found")
        var doc = Doc(
            DocumentsContract.getTreeDocumentId(folder.treeUri),
            segments[0],
            Document.MIME_TYPE_DIR,
            0,
            null,
        )
        for (segment in segments.drop(1)) {
            if (!doc.isDir) throw FsException(404, "not found")
            doc = children(folder.treeUri, doc.id).firstOrNull { it.name == segment } ?: throw FsException(404, "not found")
        }

        val virtualPath = "/" + segments.joinToString("/")
        when (op) {
            "list" -> {
                if (!doc.isDir) throw FsException(400, "not a folder")
                val entries = children(folder.treeUri, doc.id)
                    .filterNot { it.name.startsWith(".") }
                    .sortedWith(compareBy<Doc>({ !it.isDir }, { it.name.lowercase() }))
                    .map { it.toEntry("$virtualPath/${it.name}") }
                respondListing(config, requestId, entries)
            }
            "content" -> {
                if (doc.isDir) throw FsException(400, "is a folder")
                val uri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, doc.id)
                apiClient.respondFs(config, requestId, 200, ContentUriRequestBody(context, uri, doc.mime.toMediaTypeOrNull()))
            }
            else -> throw FsException(400, "unknown op")
        }
    }

    private fun children(treeUri: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val docs = mutableListOf<Doc>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
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

    private class FsException(val status: Int, message: String) : Exception(message)
}

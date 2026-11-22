package com.idroid.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Streams an inbox item (or a remote shared file) into Downloads, a picked folder, or an exact file. */
class ReceivedFileSaver(private val context: Context, private val relayApiClient: RelayApiClient) {

    /** Saves into [location]'s folder and returns a label for where it went. */
    suspend fun saveTo(location: SaveLocation, pairing: PairingConfig, item: ItemMeta): String =
        saveTo(location, filenameFor(item), item.content_type) { relayApiClient.downloadItemTo(pairing, item.id, it) }

    /** Writes into a document the user just created via ACTION_CREATE_DOCUMENT. */
    suspend fun saveToUri(target: Uri, pairing: PairingConfig, item: ItemMeta) {
        write(target) { relayApiClient.downloadItemTo(pairing, item.id, it) }
    }

    /** Same as the inbox variant, for any download that writes into an OutputStream. */
    suspend fun saveTo(location: SaveLocation, filename: String, mimeType: String, download: suspend (OutputStream) -> Unit): String =
        when (location) {
            SaveLocation.Downloads -> {
                saveToDownloads(filename, mimeType, download)
                "Downloads"
            }
            is SaveLocation.Folder -> {
                saveToTree(location.treeUri, filename, mimeType, download)
                location.displayName()
            }
            SaveLocation.AskEachTime -> error("AskEachTime needs a file picked by the user first")
        }

    /** MediaStore de-duplicates names itself ("file (1).pdf"). */
    private suspend fun saveToDownloads(filename: String, mimeType: String, download: suspend (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val target = withContext(Dispatchers.IO) {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: error("could not create download entry")
        write(target, download)
    }

    /** Document providers de-duplicate names themselves, like MediaStore. */
    private suspend fun saveToTree(treeUri: Uri, filename: String, mimeType: String, download: suspend (OutputStream) -> Unit) {
        val target = withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            DocumentsContract.createDocument(context.contentResolver, parent, mimeType, filename)
        } ?: error("could not create file in the chosen folder — pick it again in settings")
        write(target, download)
    }

    suspend fun write(target: Uri, download: suspend (OutputStream) -> Unit) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target)?.use { out -> download(out) }
                ?: error("could not open download destination")
        }
    }

    companion object {
        fun filenameFor(item: ItemMeta): String = item.filename ?: "idroid-${item.id}"
    }
}

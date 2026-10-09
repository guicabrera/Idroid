package com.idroid.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Streams an inbox item from the relay into Downloads, a picked folder, or an exact file. */
class ReceivedFileSaver(private val context: Context, private val relayApiClient: RelayApiClient) {

    /** Saves into [location]'s folder and returns a label for where it went. */
    suspend fun saveTo(location: SaveLocation, pairing: PairingConfig, item: ItemMeta): String = when (location) {
        SaveLocation.Downloads -> {
            saveToDownloads(pairing, item)
            "Downloads"
        }
        is SaveLocation.Folder -> {
            saveToTree(location.treeUri, pairing, item)
            location.displayName()
        }
        SaveLocation.AskEachTime -> error("AskEachTime needs a file picked by the user first")
    }

    /** Writes into a document the user just created via ACTION_CREATE_DOCUMENT. */
    suspend fun saveToUri(target: Uri, pairing: PairingConfig, item: ItemMeta) {
        write(target, pairing, item)
    }

    /** MediaStore de-duplicates names itself ("file (1).pdf"). */
    private suspend fun saveToDownloads(pairing: PairingConfig, item: ItemMeta) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filenameFor(item))
            put(MediaStore.Downloads.MIME_TYPE, item.content_type)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val target = withContext(Dispatchers.IO) {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: error("could not create download entry")
        write(target, pairing, item)
    }

    /** Document providers de-duplicate names themselves, like MediaStore. */
    private suspend fun saveToTree(treeUri: Uri, pairing: PairingConfig, item: ItemMeta) {
        val target = withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            DocumentsContract.createDocument(context.contentResolver, parent, item.content_type, filenameFor(item))
        } ?: error("could not create file in the chosen folder — pick it again in settings")
        write(target, pairing, item)
    }

    private suspend fun write(target: Uri, pairing: PairingConfig, item: ItemMeta) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target)?.use { out ->
                relayApiClient.downloadItemTo(pairing, item.id, out)
            } ?: error("could not open download destination")
        }
    }

    companion object {
        fun filenameFor(item: ItemMeta): String = item.filename ?: "idroid-${item.id}"
    }
}

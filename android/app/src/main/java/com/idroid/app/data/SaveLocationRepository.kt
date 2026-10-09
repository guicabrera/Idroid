package com.idroid.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where received files land when the user taps download on an inbox item.
 * Downloads unless the user picks otherwise; "Save as" always asks
 * regardless of this setting.
 */
sealed interface SaveLocation {
    data object Downloads : SaveLocation

    /** A folder picked through the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE). */
    data class Folder(val treeUri: Uri) : SaveLocation

    data object AskEachTime : SaveLocation
}

/** "primary:Documents/Idroid" → "Documents/Idroid", good enough for a label. */
fun SaveLocation.displayName(): String = when (this) {
    SaveLocation.Downloads -> "Downloads"
    SaveLocation.AskEachTime -> "Ask each time"
    is SaveLocation.Folder ->
        runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':') }
            .getOrNull()?.ifBlank { null } ?: "Custom folder"
}

/** Not a secret, so plain SharedPreferences (unlike [PairingRepository]). */
class SaveLocationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("idroid_settings", Context.MODE_PRIVATE)

    fun load(): SaveLocation = when (prefs.getString(KEY_MODE, null)) {
        MODE_FOLDER -> lastCustomFolder()?.let { SaveLocation.Folder(it) } ?: SaveLocation.Downloads
        MODE_ASK -> SaveLocation.AskEachTime
        else -> SaveLocation.Downloads
    }

    fun save(location: SaveLocation) {
        val editor = prefs.edit()
        when (location) {
            SaveLocation.Downloads -> editor.putString(KEY_MODE, MODE_DOWNLOADS)
            SaveLocation.AskEachTime -> editor.putString(KEY_MODE, MODE_ASK)
            is SaveLocation.Folder -> {
                retainAccess(location.treeUri)
                editor.putString(KEY_MODE, MODE_FOLDER).putString(KEY_FOLDER_URI, location.treeUri.toString())
            }
        }
        editor.apply()
    }

    /**
     * The last custom folder picked, kept even after switching back to
     * Downloads so the user can return to it in one tap. Dropped if the
     * user has since revoked our access to it.
     */
    fun lastCustomFolder(): Uri? {
        val uri = prefs.getString(KEY_FOLDER_URI, null)?.let(Uri::parse) ?: return null
        val stillGranted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return uri.takeIf { stillGranted }
    }

    /** A tree URI from the picker is only usable across restarts once its grant is persisted. */
    private fun retainAccess(treeUri: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.persistedUriPermissions
            .filter { it.uri != treeUri }
            .forEach { runCatching { resolver.releasePersistableUriPermission(it.uri, flags) } }
        resolver.takePersistableUriPermission(treeUri, flags)
    }

    private companion object {
        const val KEY_MODE = "save_location_mode"
        const val KEY_FOLDER_URI = "save_location_folder_uri"
        const val MODE_DOWNLOADS = "downloads"
        const val MODE_FOLDER = "folder"
        const val MODE_ASK = "ask"
    }
}

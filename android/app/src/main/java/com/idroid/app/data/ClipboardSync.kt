package com.idroid.app.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.idroid.app.IdroidApplication
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Tracks the hash of the last clipboard value this device applied from a
 * remote push (ADR-0002), so the outgoing listener
 * (ClipboardAccessibilityService) can recognize its own echo — setting the
 * clipboard locally also fires the change listener — and skip re-uploading
 * what it just received.
 */
class ClipboardSyncState {
    @Volatile
    var lastAppliedHash: String? = null

    /** Hash of the last value uploaded, so repeated copy triggers don't resend it. */
    @Volatile
    var lastSentHash: String? = null
}

/**
 * Whether copies the OS flags as sensitive (OTP codes, passwords) are
 * synced too. Off by default (ADR-0002); kept next to the other plain
 * settings in [SaveLocationRepository]'s prefs file.
 */
class ClipboardSettings(context: Context) {

    private val prefs = context.getSharedPreferences("idroid_settings", Context.MODE_PRIVATE)

    var syncSensitive: Boolean
        get() = prefs.getBoolean(KEY_SYNC_SENSITIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_SENSITIVE, value).apply()

    private companion object {
        const val KEY_SYNC_SENSITIVE = "clipboard_sync_sensitive"
    }
}

/** Clipboard pushes are held in memory on both ends; anything bigger belongs in a regular file send. */
const val MAX_CLIPBOARD_BYTES = 20 shl 20

fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

/**
 * Reads the current clipboard and uploads it to every paired device if it's
 * new text or an image (copies flagged sensitive only with
 * [ClipboardSettings.syncSensitive]). Only succeeds when called while this
 * app has window focus (Android 10+ returns null otherwise, even for
 * accessibility services) — see ClipboardReadActivity.
 */
fun pushClipboardIfChanged(context: Context) {
    val app = context.applicationContext as IdroidApplication
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val clip = clipboardManager.primaryClip ?: return
    if (clip.itemCount == 0) return

    val description = clip.description
    // Free, OS-provided signal (password managers / authenticator apps set
    // this) — skipped unless the user opted in. See ADR-0002.
    if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true &&
        !app.clipboardSettings.syncSensitive
    ) {
        return
    }

    val item = clip.getItemAt(0)
    val imageUri = item.uri?.takeIf { description.hasMimeType("image/*") }
    if (imageUri != null) {
        // Reading the image can take a moment; the clipboard's read grant on
        // the URI lasts as long as the clip stays current, so do it off the main thread.
        app.appScope.launch {
            val contentType = context.contentResolver.getType(imageUri)?.takeIf { it.startsWith("image/") } ?: "image/png"
            val bytes = runCatching { readLimited(context, imageUri) }.getOrNull() ?: return@launch
            upload(app, bytes, contentType)
        }
        return
    }

    if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    ) {
        return // only text/links and images are synced
    }
    val text = item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() } ?: return
    app.appScope.launch { upload(app, text.toByteArray(Charsets.UTF_8), "text/plain") }
}

private suspend fun upload(app: IdroidApplication, bytes: ByteArray, contentType: String) {
    val hash = sha256(bytes)
    val state = app.clipboardSyncState
    if (hash == state.lastAppliedHash) return // our own echo of a remote push
    if (hash == state.lastSentHash) return

    val pairing = app.pairingRepository.load() ?: return
    state.lastSentHash = hash
    val filename = if (contentType.startsWith("image/")) "clipboard.${extensionFor(contentType)}" else "shared-text.txt"
    // Every device in the group gets it; fetched per copy so a Mac paired a
    // moment ago gets the very next one.
    runCatching {
        for (peer in app.relayApiClient.listDevices(pairing).devices) {
            app.relayApiClient.uploadBytes(pairing, peer.id, ItemKind.CLIPBOARD, bytes, filename, contentType)
        }
    }.onFailure { if (state.lastSentHash == hash) state.lastSentHash = null }
}

/** Returns null when the image is over [MAX_CLIPBOARD_BYTES] instead of buffering it all. */
private fun readLimited(context: Context, uri: Uri): ByteArray? {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    input.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_CLIPBOARD_BYTES) return null
        }
        return out.toByteArray()
    }
}

/**
 * Builds the ClipData for a remote push. Images need a content:// URI other
 * apps can read when pasting, so they're written to a cache file served by
 * this app's FileProvider (see res/xml/file_paths.xml); only the latest one
 * is kept.
 */
fun buildIncomingClip(context: Context, bytes: ByteArray, contentType: String): ClipData {
    if (!contentType.startsWith("image/")) {
        return ClipData.newPlainText("Idroid", bytes.toString(Charsets.UTF_8))
    }
    val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "clipboard-${System.currentTimeMillis()}.${extensionFor(contentType)}")
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return ClipData.newUri(context.contentResolver, "Idroid", uri)
}

private fun extensionFor(contentType: String): String =
    MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "png"

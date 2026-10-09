package com.idroid.app.data

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
}

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

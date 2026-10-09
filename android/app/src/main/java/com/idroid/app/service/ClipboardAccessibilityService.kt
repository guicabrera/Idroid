package com.idroid.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.idroid.app.IdroidApplication
import com.idroid.app.data.ItemKind
import com.idroid.app.data.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Enabled manually once in system Settings > Accessibility (ADR-0002). Its
 * only purpose is to keep this process exempt from Android 10+'s
 * foreground-only clipboard read restriction, so clipboard changes made by
 * *other* apps can be observed in the background and synced to the paired
 * device automatically — it never inspects accessibility events or the UI
 * tree, just registers a clipboard listener once connected.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as IdroidApplication
    private lateinit var clipboardManager: ClipboardManager

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(listener)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: this service only exists for its clipboard-read exemption.
    }

    override fun onInterrupt() {}

    private fun onClipboardChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val description = clip.description
        // Free, OS-provided signal (password managers / OTP autofill set
        // this) — never synced, confirmation prompt or not. See ADR-0002.
        if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            return // only text/links are synced in this increment
        }

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (sha256(text) == app.clipboardSyncState.lastAppliedHash) return // our own echo of a remote push

        val pairing = app.pairingRepository.load() ?: return
        serviceScope.launch {
            runCatching { app.relayApiClient.uploadText(pairing, ItemKind.CLIPBOARD, text) }
        }
    }

    companion object {
        /** Used by the UI to show whether the user has enabled this service yet (it can't be requested at runtime). */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

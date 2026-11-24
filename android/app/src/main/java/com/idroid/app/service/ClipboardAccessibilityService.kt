package com.idroid.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.idroid.app.ClipboardReadActivity
import com.idroid.app.data.pushClipboardIfChanged

/**
 * Enabled manually once in system Settings > Accessibility (ADR-0002).
 *
 * Android 10+ only delivers clipboard contents to the focused app or the
 * default keyboard — an Accessibility Service is *not* exempt, so the
 * clipboard listener below only fires usefully while Idroid itself is in
 * front. To sync copies made in other apps, this service watches for
 * copy-like events (a "Copy" button tap, a "Copied" toast, the system
 * clipboard overlay) and briefly launches the invisible
 * ClipboardReadActivity, which gains focus long enough to read and upload
 * the clipboard. It only looks at the event's own text/class name, never
 * the UI tree (canRetrieveWindowContent stays false).
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private lateinit var clipboardManager: ClipboardManager
    private var lastLaunchAt = 0L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboardIfChanged(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.addPrimaryClipChangedListener(listener)
        // The system rebinds an enabled accessibility service whenever the
        // process dies, so this revives the relay connection too.
        RelaySyncService.start(this)
    }

    override fun onDestroy() {
        if (::clipboardManager.isInitialized) clipboardManager.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        if (!looksLikeCopy(event)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) return
        lastLaunchAt = now

        // Let the source app finish writing the clipboard before we read it.
        android.os.Handler(mainLooper).postDelayed({
            startActivity(
                Intent(this, ClipboardReadActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        }, READ_DELAY_MS)
    }

    override fun onInterrupt() {}

    private fun looksLikeCopy(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString().orEmpty()
        if (className.contains("clipboard", ignoreCase = true)) return true

        val text = buildString {
            event.text.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it) }
        }.lowercase()
        if (text.isBlank()) return false

        val keywords = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> CLICK_KEYWORDS
            else -> CONFIRMATION_KEYWORDS
        }
        return keywords.any { text.contains(it) }
    }

    companion object {
        private const val LAUNCH_DEBOUNCE_MS = 800L
        private const val READ_DELAY_MS = 250L

        // Labels of "Copy" actions (text selection toolbar, share sheets, app buttons).
        private val CLICK_KEYWORDS = listOf("copiar", "copy")

        // Toasts / system overlays confirming a copy.
        private val CONFIRMATION_KEYWORDS = listOf("copiado", "copiada", "copied", "área de transferência", "clipboard")

        /** Used by the UI to show whether the user has enabled this service yet (it can't be requested at runtime). */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

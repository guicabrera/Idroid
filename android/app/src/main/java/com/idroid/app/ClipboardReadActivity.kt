package com.idroid.app

import android.app.Activity
import android.os.Bundle
import com.idroid.app.data.pushClipboardIfChanged

/**
 * Invisible, momentary activity launched by ClipboardAccessibilityService
 * when it sees a "copy" action. Android 10+ only lets the focused app (or
 * the default keyboard) read the clipboard — an Accessibility Service alone
 * is not enough — so this briefly takes focus, reads and uploads the
 * clipboard, and finishes.
 */
class ClipboardReadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety net in case focus never arrives (e.g. screen locked).
        window.decorView.postDelayed({ if (!isFinishing) finishQuietly() }, 1500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        pushClipboardIfChanged(this)
        finishQuietly()
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }
}

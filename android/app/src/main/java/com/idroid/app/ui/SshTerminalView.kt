package com.idroid.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import kotlin.math.max

/**
 * Draws Termux's VT emulator and turns keys into bytes for the SSH channel.
 * Termux's own TerminalView is tied to a local process, so this is the small
 * part of it an SSH session needs: render, type, resize, scroll back.
 */
@SuppressLint("ViewConstructor")
class SshTerminalView(context: Context, private val send: (ByteArray) -> Unit, private val onResize: (Int, Int) -> Unit) : View(context) {

    private var renderer = TerminalRenderer(textSizePx(13), Typeface.MONOSPACE)
    var fontSp = 13
        set(value) {
            field = value.coerceIn(8, 28)
            renderer = TerminalRenderer(textSizePx(field), Typeface.MONOSPACE)
            resizeToView()
        }

    /** Sticky modifiers from the extra-keys row; cleared after the next key. */
    var ctrl = false
    var alt = false
    var onModifiersUsed: () -> Unit = {}

    private var topRow = 0
    private var scrollRemainder = 0f
    private var lastY = 0f

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    val emulator = TerminalEmulator(output, 80, 24, 3000, SilentClient)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    private fun textSizePx(sp: Int) = (sp * resources.displayMetrics.scaledDensity).toInt()

    /** Bytes from the Mac; call on the main thread. */
    fun append(data: ByteArray, length: Int) {
        emulator.append(data, length)
        topRow = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = resizeToView()

    private fun resizeToView() {
        if (width == 0 || height == 0) return
        val cols = max(4, (width / renderer.fontWidth).toInt())
        val rows = max(4, height / renderer.fontLineSpacing)
        if (cols != emulator.mColumns || rows != emulator.mRows) {
            emulator.resize(cols, rows)
            onResize(cols, rows)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.render(emulator, canvas, topRow, -1, -1, -1, -1)
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastY = event.y
            MotionEvent.ACTION_MOVE -> {
                // Drag scrolls back through history (main screen only; full-screen apps own their screen).
                if (emulator.isAlternateBufferActive) return true
                scrollRemainder += (event.y - lastY) / renderer.fontLineSpacing
                lastY = event.y
                val rows = scrollRemainder.toInt()
                if (rows != 0) {
                    scrollRemainder -= rows
                    topRow = (topRow - rows).coerceIn(-emulator.screen.activeTranscriptRows, 0)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.y - lastY) < 10 && scrollRemainder == 0f) showKeyboard()
        }
        return true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect: every keystroke goes straight to the shell.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.toString().forEach { typeChar(it) }
                editable?.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { send(byteArrayOf(0x7f)) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    /** One typed character, applying the sticky Ctrl/Alt from the extra-keys row. */
    fun typeChar(c: Char) {
        var code = if (c == '\n') '\r'.code else c.code
        if (ctrl) {
            code = when (c.lowercaseChar()) {
                in 'a'..'z' -> c.lowercaseChar().code - 'a'.code + 1
                ' ', '2', '@' -> 0
                '[', '3' -> 27
                '\\', '4' -> 28
                ']', '5' -> 29
                else -> code
            }
        }
        val bytes = String(Character.toChars(code)).toByteArray()
        send(if (alt) byteArrayOf(27) + bytes else bytes)
        clearModifiers()
    }

    /** Arrow keys, Tab, Esc… as the escape sequences the running program expects. */
    fun sendKey(keyCode: Int) {
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(keyCode, mod, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        if (code != null) send(code.toByteArray())
        clearModifiers()
    }

    private fun clearModifiers() {
        if (ctrl || alt) { ctrl = false; alt = false; onModifiersUsed() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed) ctrl = true
        if (event.isAltPressed) alt = true
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> { send(byteArrayOf(0x7f)); clearModifiers() }
            keyCode == KeyEvent.KEYCODE_ENTER -> typeChar('\r')
            unicode > 0 && KeyHandler.getCode(keyCode, 0, false, false) == null -> typeChar(unicode.toChar())
            else -> sendKey(keyCode)
        }
        return true
    }

    private object SilentClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

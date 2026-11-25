package com.idroid.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.idroid.app.IdroidApplication
import com.idroid.app.data.PairingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shared mouse and keyboard (ADR-0008). The Mac's pointer crosses a screen
 * edge and lands here: this service draws a pointer overlay, turns clicks,
 * drags and scrolls into touch gestures, and types into the focused field.
 * Pushing the pointer back through the edge it came in from hands control back
 * to the Mac.
 *
 * Separate from ClipboardAccessibilityService on purpose: this one needs window
 * content (to type into fields) and gestures, so the user opts in to it on its own.
 */
class InputControlService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    private var peerId: String? = null

    private lateinit var windowManager: WindowManager
    private var pointer: View? = null
    private var x = 0f
    private var y = 0f
    private var active = false
    /** Edge of the phone the pointer came in through, and goes back out of. */
    private var returnSide = "left"

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var pressed = false

    private var scrollX = 0f
    private var scrollY = 0f
    private val flushScroll = Runnable { dispatchScroll() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
        pendingJoin?.let { (config, peer) -> pendingJoin = null; connect(config, peer) }
    }

    override fun onDestroy() {
        instance = null
        socket?.close(1000, null)
        holdWifi(false)
        stopUdp()
        hidePointer()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // --- Connection -------------------------------------------------------------

    /**
     * The phone's Wi-Fi power save batches packets (RTT swings of 20–500 ms
     * were measured), which makes the pointer stutter. Hold a low-latency
     * Wi-Fi lock while the Mac's pipe is open.
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun holdWifi(on: Boolean) {
        if (on) {
            if (wifiLock?.isHeld == true) return
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "idroid:input").apply { setReferenceCounted(false); acquire() }
        } else {
            wifiLock?.takeIf { it.isHeld }?.release()
            wifiLock = null
        }
    }

    private fun connect(config: PairingConfig, peer: String) {
        socket?.close(1000, null)
        stopUdp()
        holdWifi(true)
        peerId = peer
        val app = application as IdroidApplication
        socket = app.relayApiClient.openInputSocket(config, peer, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                val msg = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                main.post { handle(msg) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)
        })
    }

    // --- Direct UDP path for pointer moves ---------------------------------------
    //
    // Moves are many, tiny and only the latest matters: going Mac → relay
    // (TCP) → phone queues them behind each other. The phone opens a UDP port,
    // advertises its LAN/Tailscale addresses and a random key in "hello", and
    // the Mac sends "key|seq|dx|dy" datagrams straight here. Stale (out of
    // order) packets are dropped. Everything else still uses the relay pipe.

    private var udp: java.net.DatagramSocket? = null
    private var udpKey = ""
    private var lastSeq = -1L

    private fun helloMessage(): String {
        startUdp()
        val port = udp?.localPort ?: 0
        val addrs = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") || it.startsWith("100.") }
        }.getOrDefault(emptyList())
        return buildJsonObject {
            put("t", "hello")
            put("udp", port)
            put("key", udpKey)
            put("addrs", kotlinx.serialization.json.JsonArray(addrs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
    }

    private fun startUdp() {
        if (udp != null) return
        val sock = runCatching { java.net.DatagramSocket(0) }.getOrNull() ?: return
        udp = sock
        udpKey = java.util.UUID.randomUUID().toString().take(12)
        lastSeq = -1L
        Thread({
            val buf = ByteArray(256)
            val packet = java.net.DatagramPacket(buf, buf.size)
            while (!sock.isClosed) {
                runCatching { sock.receive(packet) }.onFailure { return@Thread }
                val parts = String(packet.data, 0, packet.length).split('|')
                if (parts.size != 4 || parts[0] != udpKey) continue
                val seq = parts[1].toLongOrNull() ?: continue
                val dx = parts[2].toFloatOrNull() ?: continue
                val dy = parts[3].toFloatOrNull() ?: continue
                main.post {
                    if (seq > lastSeq) { lastSeq = seq; move(dx, dy) }
                }
            }
        }, "idroid-input-udp").apply { isDaemon = true; start() }
    }

    private fun stopUdp() {
        udp?.close()
        udp = null
    }

    private fun closed(webSocket: WebSocket) {
        main.post {
            if (webSocket === socket) {
                socket = null
                holdWifi(false)
                stopUdp()
                MirrorService.stop(this@InputControlService)
                leave()
            }
        }
    }

    private fun send(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        socket?.send(buildJsonObject(build).toString())
    }

    // --- Messages from the Mac --------------------------------------------------

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f

    private fun handle(msg: JsonObject) {
        when (msg.str("t")) {
            "enter" -> enter(msg.str("side") ?: "left", msg.num("y"))
            // Mirror window on the Mac (ADR-0009): absolute position, no overlay pointer.
            "pos" -> {
                val (w, h) = screenSize()
                x = (msg.num("x").coerceIn(0f, 1f) * (w - 1)); y = (msg.num("y").coerceIn(0f, 1f) * (h - 1))
                mirrorInput = true
            }
            "mirror" -> if (msg.str("on") == "true") startMirror() else MirrorService.stop(this)
            "leave" -> leave()
            "move" -> move(msg.num("dx"), msg.num("dy"))
            "down" -> { pressed = true; downAt = SystemClock.uptimeMillis(); downX = x; downY = y }
            "up" -> release()
            "rclick" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "scroll" -> {
                scrollX += msg.num("dx"); scrollY += msg.num("dy")
                main.removeCallbacks(flushScroll); main.postDelayed(flushScroll, SCROLL_IDLE_MS)
            }
            "text" -> msg.str("s")?.let { typeText(it) }
            "key" -> msg.str("k")?.let { key(it) }
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.currentWindowMetrics.bounds
            return b.width().toFloat() to b.height().toFloat()
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(size)
        return size.x.toFloat() to size.y.toFloat()
    }

    private fun enter(side: String, relY: Float) {
        val (w, h) = screenSize()
        returnSide = side
        x = if (side == "left") 1f else w - 2f
        y = (relY.coerceIn(0f, 1f) * h)
        active = true
        showPointer()
        updatePointer()
    }

    private fun leave() {
        active = false
        pressed = false
        hidePointer()
    }

    private fun move(dx: Float, dy: Float) {
        if (!active) return
        val (w, h) = screenSize()
        // Mac points → phone pixels: scale by screen density.
        val k = resources.displayMetrics.density * 0.75f
        val nx = x + dx * k
        val ny = (y + dy * k).coerceIn(0f, h - 1)
        val out = (returnSide == "left" && nx < 0f) || (returnSide == "right" && nx > w - 1)
        if (out && !pressed) {
            send { put("t", "exit"); put("y", (ny / h).toDouble()) }
            leave()
            return
        }
        x = nx.coerceIn(0f, w - 1)
        y = ny
        // One overlay relayout per frame, however many moves arrive.
        if (!redrawPending) {
            redrawPending = true
            android.view.Choreographer.getInstance().postFrameCallback { redrawPending = false; updatePointer() }
        }
    }

    private var redrawPending = false
    private var mirrorInput = false

    private fun startMirror() {
        // Bound accessibility services may start activities from the background.
        startActivity(
            Intent(this, MirrorConsentActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            ),
        )
    }

    // --- Gestures ---------------------------------------------------------------

    private fun release() {
        if (!pressed) return
        pressed = false
        val held = SystemClock.uptimeMillis() - downAt
        val path = Path().apply { moveTo(downX, downY) }
        val duration: Long
        if (hypot(x - downX, y - downY) < TAP_SLOP) {
            path.lineTo(downX, downY)
            duration = if (held >= LONG_PRESS_MS) held.coerceAtMost(2000) else 40
        } else {
            path.lineTo(x, y)
            duration = held.coerceIn(80, 1500)
        }
        gesture(path, duration)
    }

    private fun dispatchScroll() {
        if ((!active && !mirrorInput) || (scrollX == 0f && scrollY == 0f)) return
        val (w, h) = screenSize()
        // Mac "natural" deltas move content with the fingers: a swipe the same way.
        val tx = (x + scrollX * SCROLL_GAIN).coerceIn(1f, w - 2)
        val ty = (y + scrollY * SCROLL_GAIN).coerceIn(1f, h - 2)
        scrollX = 0f; scrollY = 0f
        if (abs(tx - x) < 2 && abs(ty - y) < 2) return
        gesture(Path().apply { moveTo(x, y); lineTo(tx, ty) }, 120)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        // The overlay is NOT_TOUCHABLE, so the gesture reaches the app below.
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // --- Keyboard ---------------------------------------------------------------

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /**
     * Some apps (WhatsApp's "Mensagem") report the placeholder as the field's
     * text without setting isShowingHintText; treat text equal to the hint as empty.
     */
    private fun currentText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        val text = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        return if (!hint.isNullOrEmpty() && text == hint) "" else text
    }

    private fun selection(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) { start = text.length; end = text.length }
        return minOf(start, end).coerceIn(0, text.length) to maxOf(start, end).coerceIn(0, text.length)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        setCursor(node, cursor)
    }

    private fun setCursor(node: AccessibilityNodeInfo, start: Int, end: Int = start) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })
    }

    /**
     * Android 13+: type through the focused field's InputConnection, exactly
     * like a keyboard (commitText at the cursor, real key events). No reading
     * and rewriting the field, so placeholders that apps report as text
     * (WhatsApp's "Mensagem") can't leak in. Null before 13 or with no field.
     */
    private fun imeConnection(): android.accessibilityservice.InputMethod.AccessibilityInputConnection? =
        if (android.os.Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputConnection else null

    private fun imeKey(code: Int, meta: Int = 0): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val ic = imeConnection() ?: return false
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0, meta))
        return true
    }

    private fun imeHandled(k: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33 || imeConnection() == null) return false
        val ic = imeConnection()!!
        return when (k) {
            "backspace" -> imeKey(android.view.KeyEvent.KEYCODE_DEL)
            "delete" -> imeKey(android.view.KeyEvent.KEYCODE_FORWARD_DEL)
            "enter" -> imeKey(android.view.KeyEvent.KEYCODE_ENTER)
            "tab" -> imeKey(android.view.KeyEvent.KEYCODE_TAB)
            "left" -> imeKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> imeKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> imeKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "down" -> imeKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "lineStart" -> imeKey(android.view.KeyEvent.KEYCODE_MOVE_HOME)
            "lineEnd" -> imeKey(android.view.KeyEvent.KEYCODE_MOVE_END)
            "selectAll" -> { ic.performContextMenuAction(android.R.id.selectAll); true }
            else -> false
        }
    }

    private var lastImeWarning = 0L

    /** Shown when typing arrives but no field connection exists (service not re-enabled, or no text field focused). */
    private fun warnNoIme() {
        val now = SystemClock.uptimeMillis()
        if (now - lastImeWarning < 5000) return
        lastImeWarning = now
        android.widget.Toast.makeText(
            this,
            "Idroid: toque num campo de texto. Se já tocou, desligue e ligue o serviço \"mouse e teclado do Mac\" em Acessibilidade.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun typeText(s: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Never fall back to rewriting the field here: that's what leaked
            // WhatsApp's "Mensagem" placeholder into the text.
            val ic = imeConnection() ?: return warnNoIme()
            ic.commitText(s, 1, null)
            return
        }
        val node = focusedField() ?: return
        val text = currentText(node)
        val (a, b) = selection(node, text)
        setText(node, text.substring(0, a) + s + text.substring(b), a + s.length)
    }

    private fun key(k: String) {
        when (k) {
            "esc" -> { performGlobalAction(GLOBAL_ACTION_BACK); return }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); return }
            "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); return }
            "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return }
        }
        if (imeHandled(k)) return
        if (android.os.Build.VERSION.SDK_INT >= 33) return warnNoIme()
        val node = focusedField() ?: return
        val text = currentText(node)
        val (a, b) = selection(node, text)
        when (k) {
            "backspace" -> when {
                a != b -> setText(node, text.substring(0, a) + text.substring(b), a)
                a > 0 -> setText(node, text.substring(0, a - 1) + text.substring(a), a - 1)
            }
            "delete" -> when {
                a != b -> setText(node, text.substring(0, a) + text.substring(b), a)
                a < text.length -> setText(node, text.substring(0, a) + text.substring(a + 1), a)
            }
            "enter" -> if (android.os.Build.VERSION.SDK_INT < 30 ||
                !node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) typeText("\n")
            "tab" -> typeText("\t")
            "left" -> setCursor(node, if (a != b) a else (a - 1).coerceAtLeast(0))
            "right" -> setCursor(node, if (a != b) b else (b + 1).coerceAtMost(text.length))
            "lineStart", "up" -> setCursor(node, 0)
            "lineEnd", "down" -> setCursor(node, text.length)
            "selectAll" -> setCursor(node, 0, text.length)
        }
    }

    // --- Pointer overlay --------------------------------------------------------

    private fun showPointer() {
        if (pointer != null) return
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 30, 30, 30))
                setStroke(3, Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            POINTER_PX, POINTER_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
        pointer = view
    }

    private fun updatePointer() {
        val view = pointer ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = (x - POINTER_PX / 2).toInt()
        params.y = (y - POINTER_PX / 2).toInt()
        windowManager.updateViewLayout(view, params)
    }

    private fun hidePointer() {
        pointer?.let { runCatching { windowManager.removeView(it) } }
        pointer = null
    }

    companion object {
        private const val SCROLL_GAIN = 6f
        private const val SCROLL_IDLE_MS = 60L
        private const val TAP_SLOP = 12f
        private const val LONG_PRESS_MS = 450L
        private const val POINTER_PX = 34

        @Volatile private var instance: InputControlService? = null
        @Volatile private var pendingJoin: Pair<PairingConfig, String>? = null

        /** Called when the relay says the Mac wants to hand over its pointer. */
        fun join(config: PairingConfig, peerId: String) {
            val service = instance
            if (service == null) { pendingJoin = config to peerId; return }
            service.main.post { service.connect(config, peerId) }
        }

        /** MirrorService is serving: tell the Mac where to connect. */
        fun mirrorReady(port: Int, key: String, width: Int, height: Int) {
            val service = instance ?: return
            service.main.post {
                service.send {
                    put("t", "mirror_ready"); put("port", port); put("key", key)
                    put("w", width); put("h", height)
                }
            }
        }

        fun mirrorStopped() {
            val service = instance ?: return
            service.main.post { service.mirrorInput = false; service.send { put("t", "mirror_stopped") } }
        }

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = android.content.ComponentName(context, InputControlService::class.java).flattenToString()
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

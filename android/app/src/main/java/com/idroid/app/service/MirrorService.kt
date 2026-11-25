package com.idroid.app.service

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import com.idroid.app.IdroidApplication
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Screen mirroring to the Mac (ADR-0009). Captures the screen with
 * MediaProjection (the user accepts Android's "start recording/casting?"
 * prompt every time), encodes it to H.264 and serves it over a plain TCP
 * socket the Mac connects to directly (LAN or Tailscale). The Mac learns the
 * port and a one-time key through the ADR-0008 input pipe ("mirror_ready").
 *
 * Stream: the client first sends the key + '\n'; then each frame is
 * [int32 length][int64 ptsMicros][byte flags: 1=config, 2=key][Annex B bytes].
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var server: ServerSocket? = null
    @Volatile private var client: DataOutputStream? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var running = false
    private val key = UUID.randomUUID().toString().take(16)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground (type mediaProjection) before getMediaProjection.
        val notification = Notification.Builder(this, IdroidApplication.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Espelhando a tela no Mac")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, android.os.Handler(mainLooper))
        start(mp)
        return START_NOT_STICKY
    }

    private fun start(mp: MediaProjection) {
        running = true
        val (w, h, dpi) = captureSize()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Static screens still send a frame now and then, so a fresh client sees something.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = enc.createInputSurface()
        enc.start()
        codec = enc
        display = mp.createVirtualDisplay(
            "idroid-mirror", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )

        val ss = ServerSocket(0)
        server = ss
        Thread({ acceptLoop(ss) }, "idroid-mirror-accept").apply { isDaemon = true; start() }
        Thread({ drain(enc) }, "idroid-mirror-encode").apply { isDaemon = true; start() }

        InputControlService.mirrorReady(ss.localPort, key, w, h)
    }

    /** Keeps the phone's aspect ratio, long side at most 1600 px (enough for a Mac window, light on Wi-Fi). */
    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val (rw, rh) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p); p.x to p.y
        }
        val scale = minOf(1f, 1600f / maxOf(rw, rh))
        // Encoders want even (ideally 16-aligned) sizes.
        val w = ((rw * scale).toInt() / 16) * 16
        val h = ((rh * scale).toInt() / 16) * 16
        return Triple(w, h, resources.displayMetrics.densityDpi)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock: Socket = runCatching { ss.accept() }.getOrNull() ?: return
            runCatching {
                sock.tcpNoDelay = true
                sock.soTimeout = 5000
                val line = sock.getInputStream().bufferedReader().readLine()
                if (line != key) { sock.close(); return@runCatching }
                sock.soTimeout = 0
                val out = DataOutputStream(sock.getOutputStream().buffered(1 shl 16))
                synchronized(this) {
                    runCatching { client?.close() }
                    config?.let { writeFrame(out, it, 0, FLAG_CONFIG) }
                    client = out
                }
                codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }.onFailure { runCatching { sock.close() } }
        }
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = runCatching { enc.dequeueOutputBuffer(info, 100_000) }.getOrElse { return }
            if (index < 0) continue
            val buf = enc.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset); buf.get(bytes)
                var flags = 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { flags = FLAG_CONFIG; config = bytes }
                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FLAG_KEY
                synchronized(this) {
                    client?.let { out ->
                        if (!writeFrame(out, bytes, info.presentationTimeUs, flags)) {
                            runCatching { out.close() }
                            client = null
                        }
                    }
                }
            }
            enc.releaseOutputBuffer(index, false)
        }
    }

    private fun writeFrame(out: DataOutputStream, data: ByteArray, pts: Long, flags: Int): Boolean = runCatching {
        out.writeInt(data.size); out.writeLong(pts); out.writeByte(flags); out.write(data); out.flush()
    }.isSuccess

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        synchronized(this) { runCatching { client?.close() }; client = null }
        runCatching { display?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { projection?.stop() }
        InputControlService.mirrorStopped()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.idroid.app.MIRROR_STOP"
        private const val NOTIFICATION_ID = 42
        private const val FLAG_CONFIG = 1
        private const val FLAG_KEY = 2

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Invisible activity that shows Android's screen-capture consent and hands the result to MirrorService. */
class MirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, MirrorService::class.java)
                    .putExtra(MirrorService.EXTRA_CODE, resultCode)
                    .putExtra(MirrorService.EXTRA_DATA, data),
            )
        } else {
            InputControlService.mirrorStopped()
        }
        finish()
    }
}

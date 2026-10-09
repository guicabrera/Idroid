package com.idroid.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.idroid.app.IdroidApplication
import com.idroid.app.MainActivity
import com.idroid.app.R
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.NotifyEvent
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Foreground service that keeps a websocket open to the relay so incoming
 * items surface immediately (ADR-0001) instead of waiting for the user to
 * pull-to-refresh, and applies incoming clipboard pushes (ADR-0002) to this
 * device's clipboard. It reconnects with a fixed backoff on any drop —
 * phones move between networks (Wi-Fi, mobile data, Tailscale
 * re-handshakes) far more than a desktop would.
 */
class RelaySyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private var webSocket: WebSocket? = null

    private val app get() = application as IdroidApplication

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID_SYNC, buildSyncNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        webSocket?.close(1000, "service stopping")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun connect() {
        val pairing = app.pairingRepository.load()
        if (pairing == null) {
            stopSelf()
            return
        }

        webSocket?.close(1000, "reconnecting")
        webSocket = app.relayApiClient.openWebSocket(
            pairing,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1000) scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            connect()
        }
    }

    private fun handleMessage(text: String) {
        val event = runCatching { json.decodeFromString<NotifyEvent>(text) }.getOrNull() ?: return
        if (event.event != "new_item") return
        serviceScope.launch { processInbox() }
    }

    /**
     * Fetches the current inbox and, per item, either applies it as a
     * clipboard push (ADR-0002, no user-visible inbox entry — see
     * IdroidViewModel.refreshInbox's filtering) or leaves it for the user to
     * see in the Home screen and surfaces one notification for the batch.
     */
    private suspend fun processInbox() {
        val pairing = app.pairingRepository.load() ?: return
        val items = runCatching { app.relayApiClient.listInbox(pairing) }.getOrNull() ?: return

        var hasVisibleItem = false
        for (item in items) {
            if (item.kind == ItemKind.CLIPBOARD) {
                applyIncomingClipboard(pairing, item)
            } else {
                hasVisibleItem = true
            }
        }
        if (hasVisibleItem) showIncomingItemNotification()
    }

    private suspend fun applyIncomingClipboard(pairing: PairingConfig, item: ItemMeta) {
        runCatching {
            val text = app.relayApiClient.downloadText(pairing, item.id)
            app.clipboardSyncState.lastAppliedHash = sha256(text)
            withContext(Dispatchers.Main) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Idroid", text))
            }
            app.relayApiClient.deleteItem(pairing, item.id)
        }
    }

    private fun showIncomingItemNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, IdroidApplication.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("New item in your inbox")
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_INCOMING, notification)
    }

    private fun buildSyncNotification(): Notification =
        NotificationCompat.Builder(this, IdroidApplication.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_sync_active_title))
            .setContentText(getString(R.string.notification_sync_active_text))
            .setOngoing(true)
            .build()

    private companion object {
        const val NOTIFICATION_ID_SYNC = 1
        const val NOTIFICATION_ID_INCOMING = 2
        const val RECONNECT_DELAY_MS = 5_000L
    }
}

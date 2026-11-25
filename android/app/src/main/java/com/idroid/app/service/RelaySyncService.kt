package com.idroid.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.idroid.app.IdroidApplication
import com.idroid.app.MainActivity
import com.idroid.app.R
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.NotifyEvent
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.buildIncomingClip
import com.idroid.app.data.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called on every start (activity, boot, accessibility service,
        // START_STICKY restart), so it must promote to foreground each time
        // and can't let a refused promotion crash the whole app.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_SYNC,
                buildSyncNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }
        if (promoted.isFailure) {
            Log.w(TAG, "could not start in foreground", promoted.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        // Repeated starts shouldn't tear down a healthy connection.
        if (webSocket == null) connect()
        return START_STICKY
    }

    /**
     * Swiping the app out of recents: Samsung/One UI kills the whole process
     * with it and START_STICKY often never brings it back, so an alarm (which
     * survives process death; only Force stop clears it) restarts us.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Killed by the system rather than unpaired: come back too.
        scheduleRestart(this)
        app.relayConnected.value = false
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
                // A replaced socket's late callbacks must not reconnect
                // again, or each one would spawn another connection.
                private fun isCurrent(webSocket: WebSocket) = webSocket === this@RelaySyncService.webSocket

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket)) return
                    app.relayConnected.value = true
                    // Catch up on anything that arrived while disconnected.
                    app.relayEvents.tryEmit("new_item")
                    app.relayEvents.tryEmit("devices_changed")
                    serviceScope.launch { processInbox() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrent(webSocket)) return
                    app.relayConnected.value = false
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket)) return
                    app.relayConnected.value = false
                    // Even a clean close from the relay (e.g. it restarted)
                    // should reconnect; ours in onDestroy lands in a
                    // cancelled scope and does nothing.
                    scheduleReconnect()
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
        if (event.event == "fs_request") {
            // Another device is browsing this phone's shared folders (ADR-0004).
            val pairing = app.pairingRepository.load() ?: return
            serviceScope.launch { app.fileShareResponder.handle(pairing, event) }
            return
        }
        if (event.event == "input_request") {
            // The Mac's pointer crossed onto this phone (ADR-0008).
            val pairing = app.pairingRepository.load() ?: return
            if (event.sender_id.isNotEmpty()) InputControlService.join(pairing, event.sender_id)
            return
        }
        app.relayEvents.tryEmit(event.event)
        if (event.event != "new_item") return
        serviceScope.launch { processInbox() }
    }

    /**
     * Fetches the current inbox and, per item, either applies it as a
     * clipboard push (ADR-0002, no user-visible inbox entry — see
     * IdroidViewModel.refreshInbox's filtering) or leaves it for the user to
     * see in the Home screen and surfaces one notification for the batch.
     */
    private suspend fun processInbox() = inboxMutex.withLock {
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
            val bytes = app.relayApiClient.downloadBytes(pairing, item.id)
            val hash = sha256(bytes)
            val state = app.clipboardSyncState
            // Every write makes Android 13+ show its "Copied" confirmation, so
            // skip content this phone already holds: the same copy arriving
            // from both Macs (they also share it via Universal Clipboard) or
            // bouncing back after this phone sent it.
            if (hash != state.lastAppliedHash && hash != state.lastSentHash) {
                state.lastAppliedHash = hash
                state.lastSentHash = null
                val clip = buildIncomingClip(this, bytes, item.content_type)
                withContext(Dispatchers.Main) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }
            }
            app.relayApiClient.deleteItem(pairing, item.id)
        }
    }

    /** The websocket connect and each new_item both trigger a pass; one at a time, so an item isn't applied twice. */
    private val inboxMutex = Mutex()

    private fun showIncomingItemNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, IdroidApplication.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("New item in your inbox")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_INCOMING, notification)
    }

    private fun buildSyncNotification(): Notification =
        NotificationCompat.Builder(this, IdroidApplication.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_sync_active_title))
            .setContentText(getString(R.string.notification_sync_active_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    /** Brings the existing app task to the front (or launches it), like tapping the launcher icon. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "RelaySyncService"
        private const val NOTIFICATION_ID_SYNC = 1
        private const val NOTIFICATION_ID_INCOMING = 2
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RESTART_DELAY_MS = 3_000L
        private const val RESTART_REQUEST_CODE = 3

        /**
         * Restarts the service in ~3s via AlarmManager if still paired. Starting a
         * foreground service from an alarm needs the battery exemption the home
         * screen asks for.
         */
        fun scheduleRestart(context: Context) {
            val app = context.applicationContext as IdroidApplication
            if (app.pairingRepository.load() == null) return
            val restart = PendingIntent.getForegroundService(
                context,
                RESTART_REQUEST_CODE,
                Intent(context, RelaySyncService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                restart,
            )
        }

        /**
         * Starts the service if this phone is paired. Safe to call from the
         * background (boot, accessibility service): Android 12+ may refuse
         * there, which is logged instead of crashing.
         */
        fun start(context: Context) {
            val app = context.applicationContext as IdroidApplication
            if (app.pairingRepository.load() == null) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RelaySyncService::class.java))
            }.onFailure { Log.w(TAG, "could not start sync service", it) }
        }
    }
}

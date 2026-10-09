package com.idroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.idroid.app.data.ClipboardSyncState
import com.idroid.app.data.PairingRepository
import com.idroid.app.data.RelayApiClient

class IdroidApplication : Application() {

    val pairingRepository: PairingRepository by lazy { PairingRepository(this) }
    val relayApiClient: RelayApiClient by lazy { RelayApiClient() }
    val clipboardSyncState = ClipboardSyncState()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.notification_channel_sync_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_sync_description) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                getString(R.string.notification_channel_incoming_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.notification_channel_incoming_description) },
        )
    }

    companion object {
        const val CHANNEL_SYNC = "idroid_sync"
        const val CHANNEL_INCOMING = "idroid_incoming"
    }
}

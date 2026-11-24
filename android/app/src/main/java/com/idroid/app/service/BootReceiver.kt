package com.idroid.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings RelaySyncService back after a reboot or an app update, without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> RelaySyncService.start(context)
        }
    }
}

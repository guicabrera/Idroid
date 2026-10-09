package com.idroid.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * What this phone needs to talk to the relay and to one paired recipient
 * (per ADR-0001, pairing itself is manual for now: an admin mints both
 * device tokens via `POST /admin/devices` and hands them over out of band;
 * there's no in-app QR/PIN flow yet).
 */
data class PairingConfig(
    val relayBaseUrl: String,
    val deviceToken: String,
    val recipientDeviceId: String,
    val recipientName: String,
)

/**
 * Persists [PairingConfig] in EncryptedSharedPreferences, since the device
 * token is a long-lived credential equivalent to a password.
 */
class PairingRepository(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "idroid_pairing",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): PairingConfig? {
        val relayBaseUrl = prefs.getString(KEY_RELAY_BASE_URL, null) ?: return null
        val deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null) ?: return null
        val recipientId = prefs.getString(KEY_RECIPIENT_ID, null) ?: return null
        val recipientName = prefs.getString(KEY_RECIPIENT_NAME, null) ?: return null
        return PairingConfig(relayBaseUrl, deviceToken, recipientId, recipientName)
    }

    fun save(config: PairingConfig) {
        prefs.edit()
            .putString(KEY_RELAY_BASE_URL, config.relayBaseUrl)
            .putString(KEY_DEVICE_TOKEN, config.deviceToken)
            .putString(KEY_RECIPIENT_ID, config.recipientDeviceId)
            .putString(KEY_RECIPIENT_NAME, config.recipientName)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_RELAY_BASE_URL = "relay_base_url"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_RECIPIENT_ID = "recipient_id"
        const val KEY_RECIPIENT_NAME = "recipient_name"
    }
}

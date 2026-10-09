package com.idroid.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * This phone's identity on the relay. Who it can send to isn't stored here:
 * that's every other device in its group, fetched from GET /devices, so
 * pairing more Macs never needs a new config.
 */
data class PairingConfig(
    val relayBaseUrl: String,
    val deviceToken: String,
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
        return PairingConfig(relayBaseUrl, deviceToken)
    }

    fun save(config: PairingConfig) {
        prefs.edit()
            .clear() // drops the single-recipient keys older builds stored
            .putString(KEY_RELAY_BASE_URL, config.relayBaseUrl)
            .putString(KEY_DEVICE_TOKEN, config.deviceToken)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_RELAY_BASE_URL = "relay_base_url"
        const val KEY_DEVICE_TOKEN = "device_token"
    }
}

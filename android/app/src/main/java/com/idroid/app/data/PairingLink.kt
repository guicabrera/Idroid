package com.idroid.app.data

import android.net.Uri

/**
 * What the other device's "Start pairing" QR code encodes:
 * `idroid://pair?relay=<url>&code=<6 digits>`. Reaches the app either from
 * the in-app scanner or as a deep link when scanned with the system camera.
 */
data class PairingLink(val relayBaseUrl: String, val code: String) {
    companion object {
        fun parse(raw: String?): PairingLink? {
            val uri = raw?.let(Uri::parse) ?: return null
            if (uri.scheme != "idroid" || uri.host != "pair") return null
            val relay = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return null
            val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
            return PairingLink(relay.trim(), code.trim())
        }
    }
}

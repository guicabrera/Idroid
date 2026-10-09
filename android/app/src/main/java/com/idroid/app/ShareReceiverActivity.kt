package com.idroid.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.idroid.app.data.ItemKind
import kotlinx.coroutines.launch

/**
 * The target this app registers for Android's Share Sheet (ACTION_SEND /
 * ACTION_SEND_MULTIPLE), so sharing to a paired Mac feels like sharing to
 * any other app — the Project Vision's "feel native" goal. No UI beyond a
 * dialog-themed activity (see Theme.Idroid.Dialog) and a toast; it uploads
 * and closes itself.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as IdroidApplication
        val pairing = app.pairingRepository.load()
        if (pairing == null) {
            Toast.makeText(this, "Pair Idroid with your Mac first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uris = extractUris(intent)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, "Nothing shareable in that content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            runCatching {
                uris.forEach { uri ->
                    val mimeType = contentResolver.getType(uri)
                    app.relayApiClient.uploadUri(this@ShareReceiverActivity, pairing, uri, mimeType)
                }
                if (text != null && uris.isEmpty()) {
                    app.relayApiClient.uploadText(pairing, ItemKind.TEXT, text)
                }
            }.onSuccess {
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "Sent to ${pairing.recipientName}",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { e ->
                Toast.makeText(this@ShareReceiverActivity, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) } ?: emptyList()
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            getParcelableArrayListExtra(name)
        }
}

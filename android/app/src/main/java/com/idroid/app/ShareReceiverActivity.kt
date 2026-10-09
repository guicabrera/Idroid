package com.idroid.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.idroid.app.data.ItemKind
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import com.idroid.app.ui.theme.IdroidTheme
import kotlinx.coroutines.launch

/**
 * The target this app registers for Android's Share Sheet (ACTION_SEND /
 * ACTION_SEND_MULTIPLE), so sharing to a paired Mac feels like sharing to
 * any other app — the Project Vision's "feel native" goal. With one paired
 * device it uploads straight away; with several it first asks which one
 * (or all) in a small dialog. Closes itself when done.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val app get() = application as IdroidApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pairing = app.pairingRepository.load()
        if (pairing == null) {
            finishWithToast("Pair Idroid with your Mac first", long = true)
            return
        }

        val uris = extractUris(intent)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty() && text.isNullOrBlank()) {
            finishWithToast("Nothing shareable in that content")
            return
        }

        lifecycleScope.launch {
            val devices = runCatching { app.relayApiClient.listDevices(pairing).devices }.getOrElse { e ->
                finishWithToast("Couldn't reach the relay: ${e.message}", long = true)
                return@launch
            }
            when (devices.size) {
                0 -> finishWithToast("No paired devices — add one in Idroid first", long = true)
                1 -> send(pairing, devices, uris, text)
                else -> setContent {
                    IdroidTheme {
                        DeviceChooser(devices, onChoose = { chosen -> send(pairing, chosen, uris, text) }, onCancel = ::finish)
                    }
                }
            }
        }
    }

    private fun send(pairing: PairingConfig, recipients: List<PeerDevice>, uris: List<Uri>, text: String?) {
        lifecycleScope.launch {
            runCatching {
                for (device in recipients) {
                    uris.forEach { uri ->
                        app.relayApiClient.uploadUri(this@ShareReceiverActivity, pairing, device.id, uri, contentResolver.getType(uri))
                    }
                    if (text != null && uris.isEmpty()) {
                        app.relayApiClient.uploadText(pairing, device.id, ItemKind.TEXT, text)
                    }
                }
            }.onSuccess {
                val to = if (recipients.size == 1) recipients[0].name else "all devices"
                Toast.makeText(this@ShareReceiverActivity, "Sent to $to", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@ShareReceiverActivity, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun finishWithToast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        finish()
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

@androidx.compose.runtime.Composable
private fun DeviceChooser(devices: List<PeerDevice>, onChoose: (List<PeerDevice>) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send to") },
        text = {
            Column {
                devices.forEach { device ->
                    Text(
                        device.name + if (device.online) "" else "  (offline — delivered later)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(listOf(device)) }.padding(vertical = 14.dp),
                    )
                }
                HorizontalDivider()
                Text(
                    "All devices",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(devices) }.padding(vertical = 14.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

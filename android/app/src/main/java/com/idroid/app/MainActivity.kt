package com.idroid.app

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.service.RelaySyncService
import com.idroid.app.ui.HomeScreen
import com.idroid.app.ui.IdroidViewModel
import com.idroid.app.ui.PairingScreen
import com.idroid.app.ui.theme.IdroidTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: IdroidViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            IdroidTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) sendFile(uri)
                }

                if (uiState.pairing == null) {
                    PairingScreen(
                        apiClient = (application as IdroidApplication).relayApiClient,
                        onSave = { config ->
                            viewModel.savePairing(config)
                            startSyncService()
                        },
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onRefresh = viewModel::refreshInbox,
                        onDelete = viewModel::deleteItem,
                        onDownload = { item -> downloadItem(item) },
                        onSendText = { text -> sendText(text) },
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        onForgetPairing = {
                            stopService(Intent(this, RelaySyncService::class.java))
                            viewModel.forgetPairing()
                        },
                    )
                }
            }
        }

        if (viewModel.uiState.value.pairing != null) startSyncService()
    }

    private fun startSyncService() {
        ContextCompat.startForegroundService(this, Intent(this, RelaySyncService::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun sendText(text: String) {
        val pairing = viewModel.uiState.value.pairing ?: return
        lifecycleScope.launch {
            runCatching { (application as IdroidApplication).relayApiClient.uploadText(pairing, ItemKind.TEXT, text) }
                .onSuccess { Toast.makeText(this@MainActivity, "Sent", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(this@MainActivity, "Send failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun sendFile(uri: android.net.Uri) {
        val pairing = viewModel.uiState.value.pairing ?: return
        val mimeType = contentResolver.getType(uri)
        lifecycleScope.launch {
            runCatching {
                (application as IdroidApplication).relayApiClient.uploadUri(this@MainActivity, pairing, uri, mimeType)
            }
                .onSuccess { Toast.makeText(this@MainActivity, "Sent", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(this@MainActivity, "Send failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun downloadItem(item: ItemMeta) {
        val pairing = viewModel.uiState.value.pairing ?: return
        lifecycleScope.launch {
            runCatching {
                val filename = item.filename ?: "idroid-${item.id}"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, item.content_type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val targetUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("could not create download entry")
                contentResolver.openOutputStream(targetUri)?.use { out ->
                    (application as IdroidApplication).relayApiClient.downloadItemTo(pairing, item.id, out)
                } ?: error("could not open download destination")
            }
                .onSuccess { Toast.makeText(this@MainActivity, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

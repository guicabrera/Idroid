package com.idroid.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.idroid.app.data.PairingLink
import com.idroid.app.data.PeerDevice
import com.idroid.app.service.RelaySyncService
import com.idroid.app.ui.FileBrowserScreen
import com.idroid.app.ui.HomeScreen
import com.idroid.app.ui.IdroidViewModel
import com.idroid.app.ui.PairingScreen
import com.idroid.app.ui.SendTarget
import com.idroid.app.ui.rememberInboxSaver
import com.idroid.app.ui.theme.IdroidTheme

class MainActivity : ComponentActivity() {

    private val viewModel: IdroidViewModel by viewModels()

    /** Set when the app is opened from a scanned pairing QR code; claimed by PairingScreen. */
    private var pendingPairingLink by mutableStateOf<PairingLink?>(null)

    /** Showing the pairing screen to add another device while already paired. */
    private var addingDevice by mutableStateOf(false)

    /** The device whose shared folders are being browsed (FileBrowserScreen). */
    private var browsingDevice by mutableStateOf<PeerDevice?>(null)

    /** The Mac whose terminal is open (TerminalScreen, ADR-0005). */
    private var terminalDevice by mutableStateOf<PeerDevice?>(null)

    /** Where the files picked in the open document picker go. */
    private var pendingFileTarget: SendTarget = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        if (savedInstanceState == null) handlePairingLink(intent)

        setContent {
            IdroidTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val saver = rememberInboxSaver(uiState.pairing)

                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    viewModel.sendUris(uris, pendingFileTarget)
                }

                val pairing = uiState.pairing
                val browsing = browsingDevice
                val terminal = terminalDevice
                if (pairing != null && terminal != null && !addingDevice) {
                    com.idroid.app.ui.TerminalScreen(device = terminal, pairing = pairing, onBack = { terminalDevice = null })
                } else if (pairing != null && browsing != null && !addingDevice) {
                    FileBrowserScreen(device = browsing, pairing = pairing, onBack = { browsingDevice = null })
                } else if (pairing == null || addingDevice) {
                    PairingScreen(
                        apiClient = (application as IdroidApplication).relayApiClient,
                        existing = pairing,
                        pendingLink = pendingPairingLink,
                        onLinkConsumed = { pendingPairingLink = null },
                        onPaired = { config ->
                            if (pairing == null) {
                                viewModel.savePairing(config)
                                startSyncService()
                            } else {
                                viewModel.refreshDevices()
                            }
                            addingDevice = false
                        },
                        onBack = if (pairing != null) ({ addingDevice = false }) else null,
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onRefresh = viewModel::refresh,
                        onDelete = viewModel::deleteItem,
                        onSave = saver.save,
                        onSaveAs = saver.saveAs,
                        onSendText = viewModel::sendText,
                        onPickFiles = { target ->
                            pendingFileTarget = target
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onAddDevice = { addingDevice = true },
                        onRemoveDevice = viewModel::removeDevice,
                        onBrowseDevice = { browsingDevice = it },
                        onOpenTerminal = { terminalDevice = it },
                        onLeave = { viewModel.leave { stopService(Intent(this, RelaySyncService::class.java)) } },
                        onDismissMessages = viewModel::dismissMessages,
                    )
                }
            }
        }

        if (viewModel.uiState.value.pairing != null) startSyncService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** A scanned QR either sets up this phone or, once paired, adds the device that showed it. */
    private fun handlePairingLink(intent: Intent?) {
        val link = PairingLink.parse(intent?.dataString) ?: return
        if (viewModel.uiState.value.pairing != null) addingDevice = true
        pendingPairingLink = link
    }

    private fun startSyncService() = RelaySyncService.start(this)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

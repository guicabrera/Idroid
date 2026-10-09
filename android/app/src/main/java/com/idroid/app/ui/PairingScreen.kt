package com.idroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idroid.app.data.PairStartResponse
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.RelayApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PairingMode { START, JOIN, MANUAL }

/**
 * PIN pairing (relay/README.md#api): one phone/Mac calls /pair/start and
 * shows the resulting 6-digit code, the other calls /pair/claim with it.
 * Manual entry (pasting values from `POST /admin/devices`) stays as a
 * fallback for scripting. QR code pairing is still open — see the relay's
 * README "What's deliberately out of scope here".
 */
@Composable
fun PairingScreen(apiClient: RelayApiClient, onSave: (PairingConfig) -> Unit) {
    var mode by remember { mutableStateOf(PairingMode.START) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Pair with another device", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Start pairing", selected = mode == PairingMode.START) { mode = PairingMode.START }
            ModeButton("Join with code", selected = mode == PairingMode.JOIN) { mode = PairingMode.JOIN }
            ModeButton("Manual", selected = mode == PairingMode.MANUAL) { mode = PairingMode.MANUAL }
        }
        HorizontalDivider()

        when (mode) {
            PairingMode.START -> StartPairingSection(apiClient, onSave)
            PairingMode.JOIN -> JoinPairingSection(apiClient, onSave)
            PairingMode.MANUAL -> ManualPairingSection(onSave)
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/** Device A: generate a code, show it, and poll until the other device claims it. */
@Composable
private fun StartPairingSection(apiClient: RelayApiClient, onSave: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf("http://macmini.tailnet.ts.net:8443") }
    var deviceName by remember { mutableStateOf("android-phone") }
    var started by remember { mutableStateOf<PairStartResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activeSession = started
    if (activeSession != null) {
        LaunchedEffect(activeSession.pairing.session_id) {
            while (true) {
                delay(2000)
                runCatching { apiClient.pollPairingStatus(relayBaseUrl, activeSession.pairing.session_id) }
                    .onSuccess { status ->
                        val peer = status.peer
                        if (status.claimed && peer != null) {
                            onSave(
                                PairingConfig(
                                    relayBaseUrl = relayBaseUrl.trim(),
                                    deviceToken = activeSession.device.token,
                                    recipientDeviceId = peer.id,
                                    recipientName = peer.name,
                                ),
                            )
                        }
                    }
                    .onFailure { e -> errorMessage = e.message }
            }
        }
    }

    Text(
        text = "Generates a code on this device; enter it on the other device's \"Join with code\" screen.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = relayBaseUrl,
        onValueChange = { relayBaseUrl = it },
        label = { Text("Relay base URL") },
        enabled = activeSession == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = { deviceName = it },
        label = { Text("This device's name") },
        enabled = activeSession == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (activeSession == null) {
        Button(
            onClick = {
                errorMessage = null
                isWorking = true
                scope.launch {
                    runCatching { apiClient.startPairing(relayBaseUrl.trim(), deviceName.trim()) }
                        .onSuccess { started = it }
                        .onFailure { e -> errorMessage = e.message }
                    isWorking = false
                }
            },
            enabled = !isWorking && relayBaseUrl.isNotBlank() && deviceName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isWorking) "Generating…" else "Generate code")
        }
    } else {
        Text(
            text = activeSession.pairing.code,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Waiting for the other device…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Device B: enter the code shown by the other device's "Start pairing" screen. */
@Composable
private fun JoinPairingSection(apiClient: RelayApiClient, onSave: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf("http://macmini.tailnet.ts.net:8443") }
    var deviceName by remember { mutableStateOf("android-phone") }
    var code by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(
        text = "Enter the 6-digit code shown on the other device's \"Start pairing\" screen.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = relayBaseUrl,
        onValueChange = { relayBaseUrl = it },
        label = { Text("Relay base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = { deviceName = it },
        label = { Text("This device's name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Pairing code") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Button(
        onClick = {
            errorMessage = null
            isWorking = true
            scope.launch {
                runCatching { apiClient.claimPairing(relayBaseUrl.trim(), code.trim(), deviceName.trim()) }
                    .onSuccess { claim ->
                        onSave(
                            PairingConfig(
                                relayBaseUrl = relayBaseUrl.trim(),
                                deviceToken = claim.device.token,
                                recipientDeviceId = claim.peer.id,
                                recipientName = claim.peer.name,
                            ),
                        )
                    }
                    .onFailure { e -> errorMessage = e.message }
                isWorking = false
            }
        },
        enabled = !isWorking && relayBaseUrl.isNotBlank() && deviceName.isNotBlank() && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isWorking) "Joining…" else "Join")
    }
}

/** Fallback: paste values minted directly via `POST /admin/devices` (relay/README.md#api). */
@Composable
private fun ManualPairingSection(onSave: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf("http://macmini.tailnet.ts.net:8443") }
    var deviceToken by remember { mutableStateOf("") }
    var recipientDeviceId by remember { mutableStateOf("") }
    var recipientName by remember { mutableStateOf("Mac mini") }

    val canSave = relayBaseUrl.isNotBlank() && deviceToken.isNotBlank() && recipientDeviceId.isNotBlank()

    Text(
        text = "Paste values minted directly via POST /admin/devices, bypassing the code flow.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = relayBaseUrl,
        onValueChange = { relayBaseUrl = it },
        label = { Text("Relay base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = deviceToken,
        onValueChange = { deviceToken = it },
        label = { Text("This device's token") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = recipientDeviceId,
        onValueChange = { recipientDeviceId = it },
        label = { Text("Recipient device id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = recipientName,
        onValueChange = { recipientName = it },
        label = { Text("Recipient display name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = {
            onSave(
                PairingConfig(
                    relayBaseUrl = relayBaseUrl.trim(),
                    deviceToken = deviceToken.trim(),
                    recipientDeviceId = recipientDeviceId.trim(),
                    recipientName = recipientName.trim(),
                ),
            )
        },
        enabled = canSave,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save and connect")
    }
}

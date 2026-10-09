package com.idroid.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.idroid.app.R
import com.idroid.app.data.PairStartResponse
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PairingLink
import com.idroid.app.data.RelayApiClient
import com.idroid.app.ui.theme.IdroidColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The relay this app pairs through by default (the Mac mini on the tailnet). */
private const val DEFAULT_RELAY_BASE_URL = "http://guilhermes-mac-mini-1.tail56ad8e.ts.net:8443"

private enum class PairingMode(val label: String) { JOIN("Enter code"), START("Show code"), MANUAL("Manual") }

/**
 * PIN/QR pairing (relay/README.md#api): one device calls /pair/start and
 * shows the resulting 6-digit code (the Mac also shows it as a QR code), the
 * other calls /pair/claim with it. Manual entry (pasting a token from
 * `POST /admin/devices`) stays as a fallback for scripting.
 *
 * With [existing] set, this phone is already paired and is adding another
 * device to its group: every call carries its token so the relay reuses its
 * identity, and [onPaired] gets that same config back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    apiClient: RelayApiClient,
    existing: PairingConfig?,
    pendingLink: PairingLink?,
    onLinkConsumed: () -> Unit,
    onPaired: (PairingConfig) -> Unit,
    onBack: (() -> Unit)?,
) {
    var mode by remember { mutableStateOf(PairingMode.JOIN) }
    onBack?.let { BackHandler(onBack = it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (existing != null) Text("Add a device") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (existing == null) {
                WelcomeHero()
            } else {
                Text(
                    text = "On the Mac or iPad, open Devices → Add Device and scan the code it shows.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            QrPairingSection(apiClient, existing, pendingLink, onLinkConsumed, onPaired)
            HorizontalDivider()
            Text(text = "Or pair with a code", style = MaterialTheme.typography.titleMedium)

            val modes = PairingMode.entries.filter { existing == null || it != PairingMode.MANUAL }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(m.label) }
                }
            }

            when (mode) {
                PairingMode.START -> StartPairingSection(apiClient, existing, onPaired)
                PairingMode.JOIN -> JoinPairingSection(apiClient, existing, onPaired)
                PairingMode.MANUAL -> ManualPairingSection(onPaired)
            }
        }
    }
}

/** First-run header: the logo, what Idroid does, and the two steps to get going. */
@Composable
private fun WelcomeHero() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(88.dp))
        Text("Welcome to Idroid", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Share files, text and your clipboard between this phone and your Macs — like AirDrop, across platforms.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WelcomeStep(1, "On your Mac, open Idroid and click Show Pairing Code.")
        WelcomeStep(2, "Tap Scan QR code below and point the camera at it.")
    }
}

@Composable
private fun WelcomeStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(28.dp)
                .background(IdroidColors.BrandGradient, CircleShape)
                .wrapContentHeight(),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * Scan the QR code on the other device's "Show code" screen and claim it
 * right away — no relay URL or code to type. Also claims a link that arrived
 * as a deep link from the system camera (see MainActivity).
 */
@Composable
private fun QrPairingSection(
    apiClient: RelayApiClient,
    existing: PairingConfig?,
    pendingLink: PairingLink?,
    onLinkConsumed: () -> Unit,
    onPaired: (PairingConfig) -> Unit,
) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun claim(link: PairingLink) {
        errorMessage = null
        isWorking = true
        scope.launch {
            // An already-paired phone stays on its own relay; the QR's relay is for first-time setup.
            val relay = existing?.relayBaseUrl ?: link.relayBaseUrl
            runCatching { apiClient.claimPairing(relay, link.code, Build.MODEL, existing?.deviceToken) }
                .onSuccess { claim -> onPaired(PairingConfig(relay, claim.device.token)) }
                .onFailure { e -> errorMessage = e.message }
            isWorking = false
        }
    }

    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            onLinkConsumed()
            claim(pendingLink)
        }
    }

    Button(
        onClick = {
            errorMessage = null
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val link = PairingLink.parse(barcode.rawValue)
                    if (link == null) errorMessage = "That isn't an Idroid pairing QR code" else claim(link)
                }
                .addOnFailureListener { e -> errorMessage = e.message }
        },
        enabled = !isWorking,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (isWorking) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Pairing…")
        } else {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Scan QR code")
        }
    }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** Device A: generate a code, show it, and poll until the other device claims it. */
@Composable
private fun StartPairingSection(apiClient: RelayApiClient, existing: PairingConfig?, onPaired: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf(existing?.relayBaseUrl ?: DEFAULT_RELAY_BASE_URL) }
    var deviceName by remember { mutableStateOf(Build.MODEL) }
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
                        if (status.claimed) {
                            onPaired(PairingConfig(relayBaseUrl.trim(), activeSession.device.token))
                            return@LaunchedEffect
                        }
                    }
                    .onFailure { e -> errorMessage = e.message }
            }
        }
    }

    Text(
        text = "Shows a code here for the other device to type.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (existing == null) {
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
            label = { Text("This phone's name") },
            enabled = activeSession == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (activeSession == null) {
        Button(
            onClick = {
                errorMessage = null
                isWorking = true
                scope.launch {
                    runCatching { apiClient.startPairing(relayBaseUrl.trim(), deviceName.trim(), existing?.deviceToken) }
                        .onSuccess { started = it }
                        .onFailure { e -> errorMessage = e.message }
                    isWorking = false
                }
            },
            enabled = !isWorking && relayBaseUrl.isNotBlank() && deviceName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isWorking) "Generating…" else "Show code")
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

/** Device B: enter the code shown by the other device. */
@Composable
private fun JoinPairingSection(apiClient: RelayApiClient, existing: PairingConfig?, onPaired: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf(existing?.relayBaseUrl ?: DEFAULT_RELAY_BASE_URL) }
    var deviceName by remember { mutableStateOf(Build.MODEL) }
    var code by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(
        text = "Type the 6-digit code shown on the other device.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (existing == null) {
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
            label = { Text("This phone's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text("Pairing code") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Button(
        onClick = {
            errorMessage = null
            isWorking = true
            scope.launch {
                runCatching { apiClient.claimPairing(relayBaseUrl.trim(), code.trim(), deviceName.trim(), existing?.deviceToken) }
                    .onSuccess { claim -> onPaired(PairingConfig(relayBaseUrl.trim(), claim.device.token)) }
                    .onFailure { e -> errorMessage = e.message }
                isWorking = false
            }
        },
        enabled = !isWorking && relayBaseUrl.isNotBlank() && deviceName.isNotBlank() && code.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isWorking) "Pairing…" else "Pair")
    }
}

/** Fallback: paste a token minted directly via `POST /admin/devices` (relay/README.md#api). */
@Composable
private fun ManualPairingSection(onPaired: (PairingConfig) -> Unit) {
    var relayBaseUrl by remember { mutableStateOf(DEFAULT_RELAY_BASE_URL) }
    var deviceToken by remember { mutableStateOf("") }

    Text(
        text = "Paste a device token minted via POST /admin/devices. Pair with other devices afterwards from Add device.",
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

    Button(
        onClick = { onPaired(PairingConfig(relayBaseUrl.trim(), deviceToken.trim())) },
        enabled = relayBaseUrl.isNotBlank() && deviceToken.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save and connect")
    }
}

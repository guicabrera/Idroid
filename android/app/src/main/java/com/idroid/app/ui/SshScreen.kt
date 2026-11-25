package com.idroid.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.idroid.app.IdroidApplication
import com.idroid.app.data.CommandCatalog
import com.idroid.app.data.ExecRequest
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import com.idroid.app.data.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Puts the phone's public key in ~/.ssh/authorized_keys once; the key arrives as "$1". */
private const val AUTHORIZE_KEY = """mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && (grep -qxF "$1" ~/.ssh/authorized_keys || printf '%s\n' "$1" >> ~/.ssh/authorized_keys) && echo authorized"""

private const val OPEN_SHARING = "open 'x-apple.systempreferences:com.apple.Sharing-Settings.extension'"

private sealed interface SshState {
    data object Preparing : SshState
    data object RemoteLoginOff : SshState
    data object Connecting : SshState
    data object Connected : SshState
    data class Failed(val message: String) : SshState
    data class Closed(val message: String?) : SshState
}

/**
 * Interactive shell on the Mac (vim, top, prompts…) over SSH on the tailnet
 * (ADR-0005). Setup is automatic: the phone's key is authorized through the
 * relay; the user only has to turn on Remote Login on the Mac once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshScreen(device: PeerDevice, pairing: PairingConfig, catalog: CommandCatalog, onBack: () -> Unit, active: Boolean = true) {
    BackHandler(enabled = active, onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext as IdroidApplication
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SshState>(SshState.Preparing) }
    var attempt by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SshSession?>(null) }
    var size by remember { mutableStateOf(80 to 24) }

    val view = remember {
        SshTerminalView(context, send = { bytes -> session?.write(bytes) }, onResize = { cols, rows ->
            size = cols to rows
            session?.resize(cols, rows)
        }).apply { onModifiersUsed = { ctrl = false; alt = false } }
    }

    LaunchedEffect(attempt) {
        session?.close()
        session = null
        state = SshState.Preparing
        val remoteLogin = runCatching { app.relayApiClient.commandCatalog(pairing, device.id).remoteLogin }.getOrDefault(catalog.remoteLogin)
        if (!remoteLogin) { state = SshState.RemoteLoginOff; return@LaunchedEffect }
        val host = catalog.sshHost.ifEmpty { device.name }
        val prefs = context.getSharedPreferences("idroid_settings", android.content.Context.MODE_PRIVATE)
        try {
            val publicKey = withContext(Dispatchers.Default) { app.sshKeyStore.publicKey }
            if (prefs.getString("ssh.authorized.${device.id}", null) != publicKey) {
                val out = StringBuilder()
                app.relayApiClient.execCommand(pairing, device.id, ExecRequest(command = AUTHORIZE_KEY, input = publicKey)) { out.append(it) }
                if (!out.contains("authorized")) throw IllegalStateException("couldn't authorize the key on the Mac: ${out.trim()}")
                prefs.edit().putString("ssh.authorized.${device.id}", publicKey).apply()
            }
            state = SshState.Connecting
            val s = SshSession(
                host = host,
                user = catalog.user,
                keys = app.sshKeyStore,
                knownHosts = File(context.filesDir, "ssh/known_hosts"),
                onOutput = { bytes, n -> view.post { view.append(bytes, n) } },
                onClosed = { error -> view.post { state = SshState.Closed(error) } },
            )
            withContext(Dispatchers.IO) { s.connect(size.first, size.second) }
            session = s
            state = SshState.Connected
            view.showKeyboard()
        } catch (e: Exception) {
            // The key may have been removed from authorized_keys: authorize it again on retry.
            if (e.message.orEmpty().contains("Auth fail")) prefs.edit().remove("ssh.authorized.${device.id}").apply()
            state = SshState.Failed(
                (e.message ?: e.javaClass.simpleName) +
                    "\n\nThis phone connects straight to $host over Tailscale: check that Tailscale is on here."
            )
        }
    }
    DisposableEffect(Unit) { onDispose { session?.close() } }
    // Coming back to this tab: the keyboard goes to this shell, not a hidden one.
    LaunchedEffect(active) { if (active && state == SshState.Connected) view.showKeyboard() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SSH · ${device.name}")
                        Text("${catalog.user}@${catalog.sshHost}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { view.fontSp -= 1 }) { Icon(Icons.Filled.TextDecrease, "Smaller text") }
                    IconButton(onClick = { view.fontSp += 1 }) { Icon(Icons.Filled.TextIncrease, "Larger text") }
                    IconButton(onClick = { view.showKeyboard() }) { Icon(Icons.Filled.Keyboard, "Keyboard") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                when (val s = state) {
                    SshState.Preparing, SshState.Connecting -> Overlay {
                        CircularProgressIndicator()
                        Text(if (s == SshState.Preparing) "Setting up the key…" else "Connecting…", color = Color.White)
                    }
                    SshState.RemoteLoginOff -> Overlay {
                        Text("Remote Login is off on ${device.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Turn on System Settings → General → Sharing → Remote Login on the Mac (once). The button below opens that page on the Mac.",
                            color = Color.White.copy(alpha = 0.8f))
                        Button(onClick = {
                            scope.launch { runCatching { app.relayApiClient.execCommand(pairing, device.id, ExecRequest(command = OPEN_SHARING)) {} } }
                        }) { Text("Open Sharing on the Mac") }
                        OutlinedButton(onClick = { attempt++ }) { Text("Check again") }
                    }
                    is SshState.Failed -> Overlay {
                        Text("Couldn't connect", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(s.message, color = Color.White.copy(alpha = 0.8f))
                        Button(onClick = { attempt++ }) { Text("Try again") }
                    }
                    is SshState.Closed -> Overlay {
                        Text(s.message?.let { "Disconnected: $it" } ?: "Session ended", color = Color.White)
                        Button(onClick = { attempt++ }) { Text("Reconnect") }
                    }
                    SshState.Connected -> {}
                }
            }
            // Keys a phone keyboard lacks; Ctrl and Alt stick until the next key.
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(ctrl, { ctrl = !ctrl; view.ctrl = ctrl }, label = { Text("Ctrl") })
                FilterChip(alt, { alt = !alt; view.alt = alt }, label = { Text("Alt") })
                listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
                    "←" to KeyEvent.KEYCODE_DPAD_LEFT, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                    "↑" to KeyEvent.KEYCODE_DPAD_UP, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
                    "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
                    "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
                ).forEach { (label, key) -> KeyChip(label) { view.sendKey(key) } }
                listOf("|", "~", "/", "-", "*", "&", "$", ">").forEach { c -> KeyChip(c) { view.typeChar(c[0]) } }
                KeyChip("^C") { view.ctrl = true; view.typeChar('c') }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

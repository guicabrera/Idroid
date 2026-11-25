package com.idroid.app.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.idroid.app.IdroidApplication
import com.idroid.app.data.ExecRequest
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * VS Code on the phone (ADR-0007): the same VS Code that runs in Obsidian's
 * VS Code Pane, opened over the tailnet. The Idroid Bridge plugin starts the
 * server if needed and publishes its proxy with `tailscale serve`; the URL
 * carries the proxy's secret prefix, which is all the auth the page needs.
 */
@Composable
private fun FolderPicker(current: Triple<String, String, String>?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(current?.first ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open folder in VS Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Obsidian vault")
                }
                current?.third?.let { home ->
                    androidx.compose.material3.OutlinedButton(onClick = { onPick(home) }, modifier = Modifier.fillMaxWidth()) { Text("Home (~)") }
                }
                androidx.compose.material3.OutlinedTextField(custom, { custom = it }, label = { Text("Path on the Mac") }, singleLine = true)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onPick(custom.trim().ifEmpty { null }) }) { Text("Open path") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VsCodeScreen(device: PeerDevice, pairing: PairingConfig, onBack: () -> Unit, active: Boolean = true, chrome: Boolean = true) {
    val context = LocalContext.current
    val app = context.applicationContext as IdroidApplication
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // Folder VS Code opens; null = the vault (the bridge's default).
    var folder by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Triple<String, String, String>?>(null) } // folder, vault, home
    var picker by remember { mutableStateOf(false) }

    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }
    // Back walks VS Code's own history first, then closes the tab.
    BackHandler(enabled = active) { if (web.canGoBack()) web.goBack() else onBack() }

    LaunchedEffect(attempt, folder) {
        error = null
        url = null
        val out = StringBuilder()
        runCatching {
            val result = app.relayApiClient.execCommand(
                pairing, device.id,
                ExecRequest(
                    command = "\"\$HOME/.idroid/copilot\" POST '/vscode' \"\$1\"",
                    input = kotlinx.serialization.json.buildJsonObject { folder?.let { put("folder", kotlinx.serialization.json.JsonPrimitive(it)) } }.toString(),
                ),
            ) { out.append(it) }
            val line = out.lines().lastOrNull { it.startsWith("{") } ?: throw IllegalStateException(out.toString().trim())
            val obj = Json.parseToJsonElement(line).jsonObject
            obj["error"]?.let { throw IllegalStateException(it.jsonPrimitive.content) }
            if (result.exit != 0) throw IllegalStateException(out.toString().trim())
            opened = Triple(obj["folder"]!!.jsonPrimitive.content, obj["vault"]!!.jsonPrimitive.content, obj["home"]!!.jsonPrimitive.content)
            obj["url"]!!.jsonPrimitive.content
        }.onSuccess {
            url = it
            web.loadUrl(it)
        }.onFailure {
            error = it.message ?: "couldn't start VS Code"
        }
    }

    if (picker) FolderPicker(opened, onPick = { picker = false; folder = it; attempt++ }, onDismiss = { picker = false })
    Column(Modifier.fillMaxSize().imePadding()) {
    // Folder bar: which folder this VS Code shows, tap to switch (hidden in full screen).
    if (chrome) androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(onClick = { picker = true }) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.FolderOpen, null)
            Text("  " + (opened?.let { (f, v, _) -> if (f == v) "Vault" else f.substringAfterLast('/') } ?: "…"), maxLines = 1)
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        when {
            error != null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("VS Code didn't open", style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (error!!.contains("isn't running")) "Open Obsidian on ${device.name}: VS Code runs from its VS Code Pane plugin." else error!!) +
                        "\n\nThe phone reaches it over Tailscale: check that Tailscale is on here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { attempt++ }) { Text("Try again") }
            }
            url == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Starting VS Code on ${device.name}…", Modifier.padding(top = 12.dp))
            }
        }
    }
    }
}

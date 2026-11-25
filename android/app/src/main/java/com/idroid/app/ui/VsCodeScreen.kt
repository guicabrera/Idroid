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
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VsCodeScreen(device: PeerDevice, pairing: PairingConfig, onBack: () -> Unit, active: Boolean = true) {
    val context = LocalContext.current
    val app = context.applicationContext as IdroidApplication
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

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

    LaunchedEffect(attempt) {
        error = null
        url = null
        val out = StringBuilder()
        runCatching {
            val result = app.relayApiClient.execCommand(
                pairing, device.id,
                ExecRequest(command = "\"\$HOME/.idroid/copilot\" POST '/vscode' \"\$1\"", input = "{}"),
            ) { out.append(it) }
            val line = out.lines().lastOrNull { it.startsWith("{") } ?: throw IllegalStateException(out.toString().trim())
            val obj = Json.parseToJsonElement(line).jsonObject
            obj["error"]?.let { throw IllegalStateException(it.jsonPrimitive.content) }
            if (result.exit != 0) throw IllegalStateException(out.toString().trim())
            obj["url"]!!.jsonPrimitive.content
        }.onSuccess {
            url = it
            web.loadUrl(it)
        }.onFailure {
            error = it.message ?: "couldn't start VS Code"
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
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

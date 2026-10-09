package com.idroid.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.idroid.app.data.ItemMeta
import com.idroid.app.service.ClipboardAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onDownload: (ItemMeta) -> Unit,
    onSendText: (String) -> Unit,
    onPickFile: () -> Unit,
    onForgetPairing: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Idroid — ${uiState.pairing?.recipientName ?: ""}") },
                actions = {
                    TextButton(onClick = onForgetPairing) { Text("Unpair") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ClipboardSyncStatusCard()

            SendTextRow(onSendText = onSendText)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onPickFile) { Text("Send a file") }
                Button(onClick = onRefresh) { Text(if (uiState.isRefreshing) "Refreshing…" else "Refresh inbox") }
            }

            uiState.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (uiState.inbox.isEmpty()) {
                Text("Nothing in your inbox.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.inbox) { item ->
                        InboxRow(item = item, onDelete = { onDelete(item.id) }, onDownload = { onDownload(item) })
                    }
                }
            }
        }
    }
}

/**
 * Automatic clipboard sync (ADR-0002) needs the Accessibility Service
 * enabled once in system Settings — it can't be requested like a normal
 * runtime permission. This card surfaces whether that's done yet, and
 * rechecks on every resume so it updates when the user comes back from
 * Settings.
 */
@Composable
private fun ClipboardSyncStatusCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ClipboardAccessibilityService.isEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = ClipboardAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (enabled) return // nothing to surface once it's on

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Clipboard sync is off", style = MaterialTheme.typography.titleSmall)
            Text(
                "Enable the Idroid accessibility service to sync clipboard copies with your paired device automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open Accessibility settings")
            }
        }
    }
}

@Composable
private fun SendTextRow(onSendText: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Send text or a link") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onSendText(text)
                    text = ""
                }
            },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Send") }
    }
}

@Composable
private fun InboxRow(item: ItemMeta, onDelete: () -> Unit, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.filename ?: item.kind, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${item.kind} · ${formatSize(item.size)} · ${item.created_at}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

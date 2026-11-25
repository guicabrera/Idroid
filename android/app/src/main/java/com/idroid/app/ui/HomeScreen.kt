package com.idroid.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.idroid.app.IdroidApplication
import com.idroid.app.R
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.PeerDevice
import com.idroid.app.service.ClipboardAccessibilityService
import com.idroid.app.service.InputControlService
import com.idroid.app.ui.theme.IdroidColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onSave: (ItemMeta) -> Unit,
    onSaveAs: (ItemMeta) -> Unit,
    onSendText: (String, SendTarget) -> Unit,
    onPickFiles: (SendTarget) -> Unit,
    onAddDevice: () -> Unit,
    onRemoveDevice: (PeerDevice) -> Unit,
    onBrowseDevice: (PeerDevice) -> Unit,
    onOpenTerminal: (PeerDevice) -> Unit,
    onLeave: () -> Unit,
    onDismissMessages: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    // "All devices" only makes sense with more than one; otherwise pick the one there is.
    LaunchedEffect(uiState.devices) {
        if (target != null && uiState.devices.none { it.id == target }) target = null
        if (uiState.devices.size == 1) target = uiState.devices[0].id
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(30.dp))
                        Text("Idroid", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Add device") }, onClick = { menuOpen = false; onAddDevice() })
                            DropdownMenuItem(text = { Text("Unpair this phone") }, onClick = { menuOpen = false; confirmLeave = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusHeader(uiState) }

            item { BackgroundRunStatusCard() }

            item { ClipboardSyncStatusCard() }

            item { SensitiveClipboardRow() }
            item { MacInputStatusCard() }

            item { MessageBanner(uiState, onDismissMessages) }

            item {
                if (uiState.devices.isEmpty()) {
                    NoDevicesCard(onAddDevice)
                } else {
                    SendCard(uiState.devices, target, onTargetChange = { target = it }, onSendText = { onSendText(it, target) }, onPickFiles = { onPickFiles(target) })
                }
            }

            item { SectionHeader("Inbox", if (uiState.inbox.isEmpty()) null else "${uiState.inbox.size}") }
            if (uiState.inbox.isEmpty()) {
                item {
                    Text(
                        "Files and text sent from your Macs show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.inbox, key = { it.id }) { item ->
                    InboxRow(
                        item = item,
                        senderName = uiState.deviceName(item.sender_id),
                        onSave = { onSave(item) },
                        onSaveAs = { onSaveAs(item) },
                        onDelete = { onDelete(item.id) },
                    )
                }
            }
            item { SaveLocationRow() }

            item { SectionHeader("Devices", null) }
            items(uiState.devices, key = { it.id }) { device ->
                DeviceRow(device, onBrowse = { onBrowseDevice(device) }, onTerminal = { onOpenTerminal(device) }, onRemove = { onRemoveDevice(device) })
            }
            item {
                OutlinedButton(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a Mac, iPad or another device")
                }
            }

            item { SectionHeader("Shared folders", null) }
            item { SharedFoldersCard() }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Unpair this phone?") },
            text = { Text("It stops syncing with all your devices and has to be paired again.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

/** Brand-gradient card: this phone, the relay connection, and how many devices are reachable. */
@Composable
private fun StatusHeader(uiState: HomeUiState) {
    val online = uiState.devices.count { it.online }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(IdroidColors.BrandGradient, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .padding(10.dp),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    uiState.me?.name ?: "This phone",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (uiState.isConnected) IdroidColors.Online else Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                    Text(
                        if (uiState.isConnected) "Connected" else "Offline — reconnecting",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill("${uiState.devices.size}", if (uiState.devices.size == 1) "device" else "devices")
            StatPill("$online", "online")
            StatPill("${uiState.inbox.size}", "in inbox")
        }
    }
}

@Composable
private fun StatPill(value: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
        Text(" $label", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun SectionHeader(title: String, badge: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MessageBanner(uiState: HomeUiState, onDismiss: () -> Unit) {
    val error = uiState.errorMessage
    val status = uiState.statusMessage
    val text = error ?: status ?: return
    if (error == null) {
        LaunchedEffect(status) {
            delay(4000)
            onDismiss()
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

@Composable
private fun NoDevicesCard(onAddDevice: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No devices yet", style = MaterialTheme.typography.titleMedium)
            Text("Pair your Mac (or iPad) to start sharing.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAddDevice) { Text("Add device") }
        }
    }
}

@Composable
private fun SendCard(
    devices: List<PeerDevice>,
    target: SendTarget,
    onTargetChange: (SendTarget) -> Unit,
    onSendText: (String) -> Unit,
    onPickFiles: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send to", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.size > 1) {
                    item {
                        FilterChip(selected = target == null, onClick = { onTargetChange(null) }, label = { Text("All devices") })
                    }
                }
                items(devices, key = { it.id }) { device ->
                    FilterChip(
                        selected = target == device.id,
                        onClick = { onTargetChange(device.id) },
                        label = { Text(device.name) },
                        leadingIcon = { OnlineDot(device.online) },
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Text or a link") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onSendText(text)
                            text = ""
                        },
                        enabled = text.isNotBlank(),
                    ) { Icon(Icons.Filled.Send, contentDescription = "Send text") }
                },
            )
            FilledTonalButton(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AttachFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send files")
            }
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(if (online) IdroidColors.Online else MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun InboxRow(item: ItemMeta, senderName: String, onSave: () -> Unit, onSaveAs: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = if (item.kind == ItemKind.TEXT) "Text" else item.filename ?: "File",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("from $senderName")
                        if (item.kind != ItemKind.TEXT) append(" · ${formatSize(item.size)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSave) { Icon(Icons.Filled.Download, contentDescription = "Save") }
            IconButton(onClick = onSaveAs) { Icon(Icons.Filled.SaveAs, contentDescription = "Save as") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

private fun ItemMeta.icon(): ImageVector = when {
    kind == ItemKind.TEXT -> Icons.Filled.Notes
    content_type.startsWith("image/") -> Icons.Filled.Image
    content_type.startsWith("video/") -> Icons.Filled.Movie
    else -> Icons.Filled.Description
}

@Composable
private fun DeviceRow(device: PeerDevice, onBrowse: () -> Unit, onTerminal: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(device.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OnlineDot(device.online)
                    Text(
                        if (device.online) "Online" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onBrowse, enabled = device.online) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Browse files on ${device.name}")
            }
            IconButton(onClick = onTerminal, enabled = device.online) {
                Icon(Icons.Filled.Terminal, contentDescription = "Terminal on ${device.name}")
            }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${device.name}") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("It stops syncing with your devices and has to be paired again to come back.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Best guess from the name; the relay doesn't know device types. */
private fun PeerDevice.icon(): ImageVector {
    val lower = name.lowercase()
    return when {
        "ipad" in lower -> Icons.Filled.Tablet
        "mini" in lower -> Icons.Filled.Computer
        else -> Icons.Filled.Laptop
    }
}

/**
 * With battery optimization on, Android (and OEM task killers on top of it)
 * stops RelaySyncService and blocks restarting it from the background, so
 * nothing arrives until the app is opened again. Asks once per resume until
 * the user exempts the app.
 */
@SuppressLint("BatteryLife") // sideloaded, not distributed through Play
@Composable
private fun BackgroundRunStatusCard() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = isIgnoringBatteryOptimizations(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (exempt) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryAlert, contentDescription = null)
                Text("Idroid may stop in the background", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Allow Idroid to run without battery restrictions so it stays connected and restarts on its own.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = {
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(request) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) {
                Text("Allow background use")
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Text("Clipboard sync is off", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Enable the Idroid accessibility service to sync copies with all your devices automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open Accessibility settings")
            }
        }
    }
}

/**
 * Opt-in for syncing copies the OS flags as sensitive (authenticator
 * codes, passwords), which are skipped by default (ADR-0002).
 */
@Composable
private fun SensitiveClipboardRow() {
    val app = LocalContext.current.applicationContext as IdroidApplication
    var enabled by remember { mutableStateOf(app.clipboardSettings.syncSensitive) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Sync sensitive copies", style = MaterialTheme.typography.titleSmall)
                Text(
                    "2FA codes and passwords that apps mark as sensitive are also sent to your devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    app.clipboardSettings.syncSensitive = it
                    enabled = it
                },
            )
        }
    }
}

/**
 * Folders on this phone that paired devices can browse (ADR-0004), picked
 * through the system folder picker. Read-only for the other side.
 */
@Composable
private fun SharedFoldersCard() {
    val app = LocalContext.current.applicationContext as IdroidApplication
    val repository = app.sharedFoldersRepository
    var folders by remember { mutableStateOf(repository.load()) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            repository.add(treeUri)
            folders = repository.load()
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "Your Macs can browse these folders while this phone is connected, download from them and drop new files in. They can't change or delete what's there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            folders.forEach { folder ->
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!folder.writable) {
                            Text(
                                "Read-only — pick it again with Share a folder to accept files from your Mac",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = {
                        val saveFolder = app.saveLocationRepository.lastCustomFolder()
                        repository.remove(folder, keepGrant = saveFolder == folder.treeUri)
                        folders = repository.load()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Stop sharing ${folder.name}") }
                }
            }
            TextButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share a folder")
            }
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

@Composable
private fun idroidCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

/**
 * Shared mouse and keyboard from the Mac (ADR-0008): its own accessibility
 * service, enabled once in system Settings. Shows on/off and links there.
 */
@Composable
private fun MacInputStatusCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(InputControlService.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = InputControlService.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = idroidCardColors()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Mouse e teclado do Mac", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) "Ligado: leve o ponteiro do Mac até a borda configurada para controlar o celular."
                    else "Ative o serviço \"Idroid: mouse e teclado do Mac\" em Acessibilidade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!enabled) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Ativar") }
            }
        }
    }
}

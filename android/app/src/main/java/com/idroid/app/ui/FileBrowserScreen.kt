package com.idroid.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.idroid.app.IdroidApplication
import com.idroid.app.data.FsEntry
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import com.idroid.app.data.ReceivedFileSaver
import com.idroid.app.data.RelayException
import com.idroid.app.data.SaveLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Browses the folders another device shares (ADR-0004): live, through the
 * relay, while that device is online. Tapping a file opens it; the download
 * button saves it to the configured save location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(device: PeerDevice, pairing: PairingConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as IdroidApplication
    val api = app.relayApiClient
    val scope = rememberCoroutineScope()
    val saver = remember { ReceivedFileSaver(app, api) }

    var path by rememberSaveable(device.id) { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var pendingSave by remember { mutableStateOf<FsEntry?>(null) }

    BackHandler { if (path == "/") onBack() else path = parentOf(path) }

    LaunchedEffect(device.id, path, reload) {
        loading = true
        error = null
        try {
            entries = api.listRemote(pairing, device.id, path)
        } catch (e: CancellationException) {
            // Another folder was opened before this one finished loading.
            throw e
        } catch (e: Exception) {
            entries = emptyList()
            if (e is RelayException && e.httpCode == 404 && path != "/") path = "/" else error = describe(e, device)
        } finally {
            loading = false
        }
    }

    fun download(entry: FsEntry, block: suspend () -> Unit) {
        busy = busy + entry.path
        scope.launch {
            runCatching { block() }.onFailure { e -> toast(context, "Download failed: ${describe(e, device)}") }
            busy = busy - entry.path
        }
    }

    fun open(entry: FsEntry) = download(entry) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "remote").apply { mkdirs() }
            File(dir, entry.name).also { f -> f.outputStream().use { api.downloadRemoteTo(pairing, device.id, entry.path, it) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, entry.mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, entry.name))
        } catch (_: ActivityNotFoundException) {
            toast(context, "No app can open ${entry.name}")
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingSave
        pendingSave = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        download(entry) {
            saver.write(uri) { api.downloadRemoteTo(pairing, device.id, entry.path, it) }
            toast(context, "Saved")
        }
    }

    fun save(entry: FsEntry) {
        val location = app.saveLocationRepository.load()
        if (location == SaveLocation.AskEachTime) {
            pendingSave = entry
            createDocument.launch(entry.name)
            return
        }
        download(entry) {
            val where = saver.saveTo(location, entry.name, entry.mime ?: "application/octet-stream") {
                api.downloadRemoteTo(pairing, device.id, entry.path, it)
            }
            toast(context, "Saved to $where")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path != "/") {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (path == "/") onBack() else path = parentOf(path) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> CenteredMessage(error!!, action = "Try again") { reload++ }
                entries.isEmpty() -> CenteredMessage(
                    if (path == "/") {
                        "Nothing shared on ${device.name} yet. On the Mac, open Idroid → Files → Shared Folders and add a folder."
                    } else {
                        "This folder is empty."
                    },
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            busy = entry.path in busy,
                            onClick = { if (entry.is_dir) path = entry.path else if (entry.path !in busy) open(entry) },
                            onSave = { save(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FsEntry, busy: Boolean, onClick: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            entry.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                .padding(8.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                if (entry.is_dir) null else formatBytes(entry.size),
                entry.modified?.let(::formatDate),
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.is_dir) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onSave) { Icon(Icons.Filled.Download, contentDescription = "Save ${entry.name}") }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

private fun FsEntry.icon(): ImageVector {
    val type = mime.orEmpty()
    return when {
        is_dir -> Icons.Filled.Folder
        type.startsWith("image/") -> Icons.Filled.Image
        type.startsWith("video/") -> Icons.Filled.Movie
        type.startsWith("audio/") -> Icons.Filled.MusicNote
        type == "application/pdf" -> Icons.Filled.PictureAsPdf
        else -> Icons.Filled.Description
    }
}

private fun parentOf(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return "/" + segments.dropLast(1).joinToString("/")
}

private fun describe(e: Throwable, device: PeerDevice): String = when ((e as? RelayException)?.httpCode) {
    503 -> "${device.name} is offline."
    504 -> "${device.name} didn't answer. Make sure Idroid is running on it."
    null -> listOfNotNull(e::class.java.simpleName, e.message).joinToString(": ")
    else -> "${e.message} (${e.httpCode})"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private fun formatDate(iso: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrNull()

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

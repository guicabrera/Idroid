package com.idroid.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browses the folders another device shares (ADR-0004): live, through the
 * relay, while that device is online. Tapping a file opens it; the download
 * button saves it to the configured save location. Folders already opened are
 * shown straight from memory (with their scroll position) and refreshed in
 * the background.
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
    val listings = remember(device.id) { mutableStateMapOf<String, List<FsEntry>>() }
    val scrollPositions = remember(device.id) { mutableMapOf<String, Pair<Int, Int>>() }
    val listState = remember(device.id, path) {
        val (index, offset) = scrollPositions[path] ?: (0 to 0)
        LazyListState(index, offset)
    }
    val entries = listings[path]
    var loading by remember { mutableStateOf(false) }
    var loadSeq by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // Files being downloaded: progress 0..1, or null while the size is unknown.
    val progress = remember { mutableStateMapOf<String, Float?>() }
    val jobs = remember { mutableMapOf<String, Job>() }
    var pendingSave by remember { mutableStateOf<FsEntry?>(null) }

    fun navigate(to: String) {
        scrollPositions[path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        error = null
        missing = false
        path = to
    }

    BackHandler { if (path == "/") onBack() else navigate(parentOf(path)) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { pruneOpenedFiles(context) } }

    LaunchedEffect(device.id, path, reload) {
        val seq = ++loadSeq
        loading = true
        error = null
        missing = false
        try {
            listings[path] = api.listRemote(pairing, device.id, path)
        } catch (e: CancellationException) {
            // Another folder was opened before this one finished loading.
            throw e
        } catch (e: Exception) {
            if (e is RelayException && e.httpCode == 404 && path != "/") {
                // Moved, deleted or no longer shared: say so instead of silently jumping elsewhere.
                listings.keys.removeAll { it == path || it.startsWith("$path/") }
                missing = true
            } else if (listings[path] != null) {
                toast(context, "Couldn't refresh: ${describe(e, device)}")
            } else {
                error = describe(e, device)
            }
        } finally {
            if (seq == loadSeq) loading = false
        }
    }

    fun download(entry: FsEntry, block: suspend (onProgress: (Float) -> Unit) -> Unit) {
        progress[entry.path] = null
        jobs[entry.path] = scope.launch {
            try {
                block { fraction -> progress[entry.path] = fraction }
            } catch (_: CancellationException) {
                // Cancelled from the row's progress button.
            } catch (e: Exception) {
                toast(context, "Download failed: ${describe(e, device)}")
            } finally {
                progress.remove(entry.path)
                jobs.remove(entry.path)
            }
        }
    }

    fun open(entry: FsEntry) = download(entry) { onProgress ->
        val file = withContext(Dispatchers.IO) {
            // One folder per remote path, so two files with the same name don't overwrite each other.
            val dir = File(context.cacheDir, "$OPENED_DIR/${entry.path.hashCode().toUInt()}").apply { mkdirs() }
            val target = File(dir, entry.name)
            try {
                target.outputStream().use { api.downloadRemoteTo(pairing, device.id, entry.path, it, onProgress) }
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            target
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
        download(entry) { onProgress ->
            try {
                saver.write(uri) { api.downloadRemoteTo(pairing, device.id, entry.path, it, onProgress) }
            } catch (e: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                throw e
            }
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
        download(entry) { onProgress ->
            val where = saver.saveTo(location, entry.name, entry.mime ?: "application/octet-stream") {
                api.downloadRemoteTo(pairing, device.id, entry.path, it, onProgress)
            }
            toast(context, "Saved to $where")
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { if (path == "/") onBack() else navigate(parentOf(path)) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    },
                )
                Breadcrumbs(path, onNavigate = ::navigate)
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (loading && entries != null) LinearProgressIndicator(Modifier.fillMaxSize())
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { reload++ },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                missing -> CenteredMessage(
                    "This folder is no longer available on ${device.name}. It may have been moved, deleted or unshared.",
                    action = "Go to shared folders",
                ) { navigate("/") }
                error != null && entries == null -> CenteredMessage(error!!, action = "Try again") { reload++ }
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                entries.isEmpty() -> CenteredMessage(
                    if (path == "/") {
                        "Nothing shared on ${device.name} yet. On the Mac, open Idroid → Files → Shared Folders and add a folder."
                    } else {
                        "This folder is empty."
                    },
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        val downloading = entry.path in progress
                        EntryRow(
                            entry = entry,
                            downloading = downloading,
                            progress = progress[entry.path],
                            onClick = { if (entry.is_dir) navigate(entry.path) else if (!downloading) open(entry) },
                            onSave = { save(entry) },
                            onCancel = { jobs[entry.path]?.cancel() },
                        )
                    }
                }
            }
        }
    }
}

/** The current path as tappable segments, so any enclosing folder is one tap away. */
@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = listOf("Shared" to "/") + segments.mapIndexed { i, name ->
            name to "/" + segments.take(i + 1).joinToString("/")
        }
        crumbs.forEachIndexed { i, (name, target) ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val current = i == crumbs.lastIndex
            TextButton(onClick = { onNavigate(target) }, enabled = !current) {
                Text(
                    name,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: FsEntry,
    downloading: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
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
        when {
            entry.is_dir -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
            downloading -> IconButton(onClick = onCancel) {
                Box(contentAlignment = Alignment.Center) {
                    if (progress != null) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download of ${entry.name}", modifier = Modifier.size(14.dp))
                }
            }
            else -> IconButton(onClick = onSave) { Icon(Icons.Filled.Download, contentDescription = "Save ${entry.name}") }
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

private const val OPENED_DIR = "remote"

/** Opened files only need to outlive the viewer app reading them; drop anything older than a day. */
private fun pruneOpenedFiles(context: Context) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    File(context.cacheDir, OPENED_DIR).listFiles()?.forEach { dir ->
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete()
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

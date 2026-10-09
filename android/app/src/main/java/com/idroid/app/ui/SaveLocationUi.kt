package com.idroid.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.idroid.app.IdroidApplication
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.ReceivedFileSaver
import com.idroid.app.data.SaveLocation
import com.idroid.app.data.displayName
import kotlinx.coroutines.launch

/** What an inbox row's download actions call into. */
class InboxSaver internal constructor(
    val save: (ItemMeta) -> Unit,
    val saveAs: (ItemMeta) -> Unit,
)

/**
 * [InboxSaver.save] uses the configured [SaveLocation] (Downloads by
 * default) and only asks when it's [SaveLocation.AskEachTime];
 * [InboxSaver.saveAs] always asks.
 */
@Composable
fun rememberInboxSaver(pairing: PairingConfig?): InboxSaver {
    val context = LocalContext.current
    val app = context.applicationContext as IdroidApplication
    val scope = rememberCoroutineScope()
    val fileSaver = remember { ReceivedFileSaver(app, app.relayApiClient) }
    var pendingItem by remember { mutableStateOf<ItemMeta?>(null) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = pendingItem
        pendingItem = null
        if (uri == null || item == null || pairing == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fileSaver.saveToUri(uri, pairing, item) }
                .onSuccess { toast(context, "Saved") }
                .onFailure { e -> toast(context, "Download failed: ${e.message}", long = true) }
        }
    }

    fun ask(item: ItemMeta) {
        pendingItem = item
        createDocument.launch(ReceivedFileSaver.filenameFor(item))
    }

    return remember(pairing) {
        InboxSaver(
            save = save@{ item ->
                pairing ?: return@save
                val location = app.saveLocationRepository.load()
                if (location == SaveLocation.AskEachTime) return@save ask(item)
                scope.launch {
                    runCatching { fileSaver.saveTo(location, pairing, item) }
                        .onSuccess { where -> toast(context, "Saved to $where") }
                        .onFailure { e -> toast(context, "Download failed: ${e.message}", long = true) }
                }
            },
            saveAs = { item -> if (pairing != null) ask(item) },
        )
    }
}

/** "Saving to: Downloads · Change" — opens a picker for the default save location. */
@Composable
fun SaveLocationRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = (context.applicationContext as IdroidApplication).saveLocationRepository
    var location by remember { mutableStateOf(repository.load()) }
    var showDialog by remember { mutableStateOf(false) }

    fun update(newLocation: SaveLocation) {
        repository.save(newLocation)
        location = newLocation
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) update(SaveLocation.Folder(treeUri))
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Saving to: ${location.displayName()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showDialog = true }) { Text("Change") }
    }

    if (showDialog) {
        val lastFolder = repository.lastCustomFolder()
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Save received files to") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocationOption("Downloads", selected = location == SaveLocation.Downloads) {
                        update(SaveLocation.Downloads)
                        showDialog = false
                    }
                    val folderLabel = lastFolder?.let { "Custom folder (${SaveLocation.Folder(it).displayName()})" }
                        ?: "Custom folder…"
                    LocationOption(folderLabel, selected = location is SaveLocation.Folder) {
                        if (lastFolder != null && location !is SaveLocation.Folder) {
                            update(SaveLocation.Folder(lastFolder))
                        } else {
                            pickFolder.launch(lastFolder)
                        }
                        showDialog = false
                    }
                    LocationOption("Ask each time", selected = location == SaveLocation.AskEachTime) {
                        update(SaveLocation.AskEachTime)
                        showDialog = false
                    }
                    Text(
                        "“Save as” on an inbox item always asks, whatever is set here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickFolder.launch(lastFolder)
                    showDialog = false
                }) { Text("Choose folder…") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun LocationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun toast(context: Context, message: String, long: Boolean = false) {
    Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

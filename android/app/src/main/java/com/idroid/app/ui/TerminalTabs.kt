package com.idroid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idroid.app.IdroidApplication
import com.idroid.app.data.CommandCatalog
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import kotlinx.coroutines.launch

private sealed interface TermTab {
    val id: Int
    val title: String
    class Shell(override val id: Int, override val title: String) : TermTab
    class Ssh(override val id: Int, override val title: String, val catalog: CommandCatalog) : TermTab
    class VsCode(override val id: Int, override val title: String) : TermTab
}

/**
 * Several terminals on one Mac at once: relay terminals (with Commands, AI
 * and Copilot) and interactive SSH shells, each in its own tab. Every tab
 * stays composed while hidden, so commands keep running and SSH sessions stay
 * connected; hidden tabs are laid out at zero size, which the SSH view
 * ignores, so the remote PTY keeps its size.
 */
@Composable
fun TerminalTabs(device: PeerDevice, pairing: PairingConfig, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as IdroidApplication
    val scope = rememberCoroutineScope()
    var nextId by remember { mutableIntStateOf(2) }
    var shells by remember { mutableIntStateOf(1) }
    var sshes by remember { mutableIntStateOf(0) }
    val tabs = remember { mutableStateListOf<TermTab>(TermTab.Shell(1, "Terminal 1")) }
    var selected by remember { mutableIntStateOf(1) }
    var addMenu by remember { mutableStateOf(false) }

    fun addShell() {
        shells++
        tabs += TermTab.Shell(nextId, "Terminal $shells").also { selected = it.id }
        nextId++
    }

    fun addSsh(catalog: CommandCatalog) {
        sshes++
        tabs += TermTab.Ssh(nextId, "SSH $sshes", catalog).also { selected = it.id }
        nextId++
    }

    fun addVsCode() {
        tabs += TermTab.VsCode(nextId, "VS Code").also { selected = it.id }
        nextId++
    }

    fun close(tab: TermTab) {
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tabs.isEmpty()) { onBack(); return }
        if (selected == tab.id) selected = tabs[(index - 1).coerceAtLeast(0)].id
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    edgePadding = 4.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab.id == selected,
                            onClick = { selected = tab.id },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        when (tab) { is TermTab.Ssh -> Icons.Filled.Computer; is TermTab.VsCode -> Icons.Filled.Code; else -> Icons.Filled.Terminal },
                                        null, Modifier.size(16.dp),
                                    )
                                    Text(" ${tab.title}", fontSize = 13.sp)
                                    IconButton(onClick = { close(tab) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.Close, "Close ${tab.title}", Modifier.size(14.dp))
                                    }
                                }
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "New tab") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New terminal") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = { addMenu = false; addShell() },
                        )
                        DropdownMenuItem(
                            text = { Text("VS Code") },
                            leadingIcon = { Icon(Icons.Filled.Code, null) },
                            onClick = { addMenu = false; addVsCode() },
                        )
                        DropdownMenuItem(
                            text = { Text("New SSH session") },
                            leadingIcon = { Icon(Icons.Filled.Computer, null) },
                            onClick = {
                                addMenu = false
                                scope.launch {
                                    runCatching { app.relayApiClient.commandCatalog(pairing, device.id) }
                                        .onSuccess(::addSsh)
                                }
                            },
                        )
                    }
                }
            }
        }
        // The tab row already took the status bar: screens below must not pad for it again.
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) {
            tabs.forEach { tab ->
                key(tab.id) {
                    val active = tab.id == selected
                    Box(if (active) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                        when (tab) {
                            is TermTab.Shell -> TerminalScreen(
                                device, pairing,
                                onBack = onBack,
                                active = active,
                                onOpenSsh = ::addSsh,
                            )
                            is TermTab.Ssh -> SshScreen(device, pairing, tab.catalog, onBack = { close(tab) }, active = active)
                            is TermTab.VsCode -> VsCodeScreen(device, pairing, onBack = { close(tab) }, active = active)
                        }
                    }
                }
            }
        }
    }
}

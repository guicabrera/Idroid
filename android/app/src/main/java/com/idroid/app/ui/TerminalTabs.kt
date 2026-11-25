package com.idroid.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
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
    class Copilot(override val id: Int, override val title: String, val startNew: Boolean) : TermTab
}

/**
 * Several terminals on one Mac at once: relay terminals (with Commands, AI
 * and Copilot) and interactive SSH shells, each in its own tab. Every tab
 * stays composed while hidden, so commands keep running and SSH sessions stay
 * connected; hidden tabs sit transparent under the visible one.
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
    // Full screen: no tab row, no title bars, no system bars — just the terminal/VS Code.
    var fullscreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            // Bars come back over the content with a swipe from the edge, then hide again.
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

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

    var copilots by remember { mutableIntStateOf(0) }

    /** The first Copilot tab shows the Mac's open chat; later ones start a new chat. */
    fun addCopilot() {
        copilots++
        tabs += TermTab.Copilot(nextId, "Copilot $copilots", startNew = tabs.any { it is TermTab.Copilot })
            .also { selected = it.id }
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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                val selectedIndex = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.weight(1f),
                    edgePadding = 4.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    // The default indicator reads tabPositions[selectedIndex] before a just-added
                    // tab has been measured (IndexOutOfBounds): skip that one frame instead.
                    indicator = { positions ->
                        positions.getOrNull(selectedIndex)?.let {
                            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it))
                        }
                    },
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab.id == selected,
                            onClick = { selected = tab.id },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        when (tab) {
                                            is TermTab.Ssh -> Icons.Filled.Computer
                                            is TermTab.VsCode -> Icons.Filled.Code
                                            is TermTab.Copilot -> Icons.Filled.SmartToy
                                            else -> Icons.Filled.Terminal
                                        },
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
                IconButton(onClick = { fullscreen = true }) { Icon(Icons.Filled.Fullscreen, "Full screen") }
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "New tab") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New terminal") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = { addMenu = false; addShell() },
                        )
                        DropdownMenuItem(
                            text = { Text("Copilot") },
                            leadingIcon = { Icon(Icons.Filled.SmartToy, null) },
                            onClick = { addMenu = false; addCopilot() },
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
                    // Every tab stays placed full size, stacked: the visible one on top (its
                    // Scaffold surface takes the touches), the others transparent underneath.
                    // 0-size or unplaced tabs drew over the tab row (native views included).
                    Box(Modifier.fillMaxSize().zIndex(if (active) 1f else 0f).graphicsLayer { alpha = if (active) 1f else 0f }) {
                        when (tab) {
                            is TermTab.Shell -> TerminalScreen(
                                device, pairing,
                                onBack = onBack,
                                active = active,
                                onOpenSsh = ::addSsh,
                                chrome = !fullscreen,
                                onOpenCopilot = ::addCopilot,
                            )
                            is TermTab.Ssh -> SshScreen(device, pairing, tab.catalog, onBack = { close(tab) }, active = active, chrome = !fullscreen)
                            is TermTab.Copilot -> CopilotScreen(
                                device, pairing, onBack = { close(tab) }, active = active,
                                startNew = tab.startNew, chrome = !fullscreen,
                            )
                            is TermTab.VsCode -> VsCodeScreen(device, pairing, onBack = { close(tab) }, active = active, chrome = !fullscreen)
                        }
                    }
                }
            }
        }
    }
    if (fullscreen) {
        // Small, translucent way out; Back also leaves full screen first.
        IconButton(
            onClick = { fullscreen = false },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) { Icon(Icons.Filled.FullscreenExit, "Exit full screen", tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
    }
    // Registered after the tabs, so it takes Back before them.
    BackHandler(enabled = fullscreen) { fullscreen = false }
}

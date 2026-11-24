package com.idroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idroid.app.IdroidApplication
import com.idroid.app.data.AiModel
import com.idroid.app.data.AiProvider
import com.idroid.app.data.listModels
import com.idroid.app.data.AiSettings
import com.idroid.app.data.CommandAction
import com.idroid.app.data.CommandCatalog
import com.idroid.app.data.ExecRequest
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import com.idroid.app.data.TerminalAgent
import com.idroid.app.data.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One block of the terminal transcript. */
private sealed interface Entry {
    class Run(val command: String, val cwd: String) : Entry {
        var output by mutableStateOf("")
        var exit by mutableStateOf<Int?>(null)
        var error by mutableStateOf<String?>(null)
    }
    class Ai(val text: String) : Entry
    class Proposal(val call: ToolCall) : Entry {
        var command by mutableStateOf(call.command)
        var decision by mutableStateOf<Boolean?>(null)
        val answer = CompletableDeferred<Boolean>()
    }
    class Note(val text: String) : Entry
}

/** How much output goes back to the model per command. */
private const val MAX_TOOL_OUTPUT = 8_000

/**
 * Remote terminal for a Mac (ADR-0005): free commands with autocomplete,
 * the Mac's named commands, and an AI agent that proposes commands and runs
 * them once approved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(device: PeerDevice, pairing: PairingConfig, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val app = LocalContext.current.applicationContext as IdroidApplication
    val api = app.relayApiClient
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<CommandCatalog?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var cwd by remember { mutableStateOf("~") }
    val entries = remember { mutableStateListOf<Entry>() }
    val history = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var aiMode by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var running by remember { mutableStateOf<Job?>(null) }
    var tab by remember { mutableStateOf(0) }
    var aiSettings by remember { mutableStateOf(app.aiSettingsRepository.load()) }
    var showAiSettings by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf<TerminalAgent?>(null) }
    var askInput by remember { mutableStateOf<CommandAction?>(null) }
    var sshOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun loadCatalog() {
        catalogError = null
        runCatching { api.commandCatalog(pairing, device.id) }
            .onSuccess { catalog = it }
            .onFailure { catalogError = it.message ?: "could not reach ${device.name}" }
    }
    LaunchedEffect(device.id) { loadCatalog() }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }

    /** Runs on the Mac, streaming into a new transcript block; returns what the model should see. */
    suspend fun exec(request: ExecRequest, label: String): String {
        val run = Entry.Run(label, cwd)
        entries += run
        return runCatching {
            api.execCommand(pairing, device.id, request.copy(cwd = cwd)) { chunk -> run.output += chunk }
        }.fold(
            onSuccess = { result ->
                run.exit = result.exit
                if (result.cwd.isNotEmpty()) cwd = result.cwd.replaceFirst(catalog?.home ?: "\u0000", "~")
                run.output.takeLast(MAX_TOOL_OUTPUT) + "\n[exit ${result.exit}]"
            },
            onFailure = { e ->
                run.error = e.message ?: "failed"
                "[error: ${run.error}]"
            },
        )
    }

    fun runShell(command: String) {
        if (command.isBlank()) return
        history.remove(command); history += command
        running = scope.launch { exec(ExecRequest(command = command), command); running = null }
    }

    fun runAction(action: CommandAction, value: String?) {
        tab = 0
        running = scope.launch {
            exec(ExecRequest(actionId = action.id, input = value), action.name + (value?.let { " · $it" } ?: ""))
            running = null
        }
    }

    fun askAgent(prompt: String) {
        if (!aiSettings.configured) { showAiSettings = true; return }
        val current = agent ?: TerminalAgent(aiSettings, catalog?.home ?: "~").also { agent = it }
        entries += Entry.Note("› $prompt")
        running = scope.launch {
            try {
                var reply = current.send(prompt, cwd)
                repeat(15) {
                    if (reply.text.isNotBlank()) entries += Entry.Ai(reply.text)
                    if (reply.calls.isEmpty()) return@launch
                    val results = reply.calls.map { call ->
                        val proposal = Entry.Proposal(call)
                        entries += proposal
                        val approved = if (aiSettings.autoRun) true else proposal.answer.await()
                        proposal.decision = approved
                        call to if (approved) exec(ExecRequest(command = proposal.command), proposal.command)
                        else "[the user declined to run this command]"
                    }
                    reply = current.results(results, cwd)
                }
                entries += Entry.Note("Stopped after 15 steps.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                entries += Entry.Note("AI error: ${e.message}")
            } finally {
                running = null
            }
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || running != null) return
        input = ""
        suggestions = emptyList()
        if (aiMode) askAgent(text) else runShell(text)
    }

    // Autocomplete: history first, then the Mac's own completion of the last word.
    LaunchedEffect(input, aiMode, catalog?.terminalEnabled) {
        if (aiMode || input.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        val local = history.asReversed().filter { it.startsWith(input) && it != input }.distinct().take(5)
        suggestions = local
        if (catalog?.terminalEnabled != true) return@LaunchedEffect
        delay(250)
        val remote = runCatching { api.completeCommand(pairing, device.id, input, cwd) }.getOrDefault(emptyList())
        val head = input.substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
        suggestions = (local + remote.map { head + it + if (it.endsWith("/")) "" else " " })
            .filter { it.trimEnd() != input.trimEnd() }.distinct().take(25)
    }

    val loaded = catalog
    if (sshOpen && loaded != null) {
        SshScreen(device, pairing, loaded, onBack = { sshOpen = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name)
                        Text(cwd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { sshOpen = true }, enabled = catalog != null) { Icon(Icons.Filled.Computer, "SSH terminal (interactive)") }
                    IconButton(onClick = { scope.launch { loadCatalog() } }) { Icon(Icons.Filled.Refresh, "Reload commands") }
                    IconButton(onClick = { showAiSettings = true }) { Icon(Icons.Filled.Settings, "AI settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Terminal") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Commands") })
            }
            catalogError?.let { Banner("Couldn't load commands: $it") }
            if (catalog?.terminalEnabled == false) {
                Banner("The terminal is turned off on ${device.name} (Idroid → Settings → Commands on the Mac). Only its named commands run.")
            }
            if (tab == 1) {
                CommandList(catalog, onRun = { action -> if (action.takesInput) askInput = action else runAction(action, null) })
                return@Column
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (entries.isEmpty()) item {
                    Text(
                        "Run commands on ${device.name}, or switch to AI and describe what you want: " +
                            "\"pull every repo in ~/repos\", \"what's eating disk space?\", \"open today's daily note\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries) { entry ->
                    when (entry) {
                        is Entry.Run -> RunBlock(entry)
                        is Entry.Ai -> Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        is Entry.Note -> Text(entry.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        is Entry.Proposal -> ProposalCard(entry)
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { s ->
                        AssistChip(onClick = { input = s }, label = { Text(s.trim().substringAfterLast(' ').ifEmpty { s.trim() }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = aiMode,
                    onClick = { aiMode = !aiMode },
                    label = { Text(if (aiMode) "AI" else "$") },
                    leadingIcon = { Icon(if (aiMode) Icons.Filled.AutoAwesome else Icons.Filled.Terminal, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (aiMode) "Ask ${aiSettings.provider.label}…" else "command") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (aiMode) FontFamily.Default else FontFamily.Monospace),
                    singleLine = !aiMode,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (aiMode) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        autoCorrectEnabled = aiMode,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                val job = running
                if (job != null) {
                    IconButton(onClick = { job.cancel(); running = null }) { Icon(Icons.Filled.Stop, "Stop") }
                } else {
                    IconButton(onClick = ::submit, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Run") }
                }
            }
        }
    }

    askInput?.let { action ->
        var value by remember(action.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askInput = null },
            title = { Text(action.name) },
            text = { OutlinedTextField(value, { value = it }, label = { Text("Input") }, singleLine = true) },
            confirmButton = { Button(onClick = { askInput = null; runAction(action, value) }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { askInput = null }) { Text("Cancel") } },
        )
    }

    if (showAiSettings) {
        AiSettingsDialog(
            initial = aiSettings,
            keyFor = app.aiSettingsRepository::keyFor,
            modelFor = app.aiSettingsRepository::modelFor,
            onDismiss = { showAiSettings = false },
            onSave = { saved ->
                app.aiSettingsRepository.save(saved)
                aiSettings = saved
                agent = null // a new provider or model starts a new conversation
                showAiSettings = false
            },
        )
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun RunBlock(run: Entry.Run) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${run.cwd} $ ${run.command}", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            when {
                run.error != null -> Text("error", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                run.exit == null -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                run.exit != 0 -> Text("exit ${run.exit}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        val text = run.error ?: run.output.trimEnd()
        if (text.isNotEmpty()) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun ProposalCard(p: Entry.Proposal) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            p.call.why?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (p.decision == null) {
                OutlinedTextField(p.command, { p.command = it }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { p.answer.complete(true) }) { Text("Run") }
                    OutlinedButton(onClick = { p.answer.complete(false) }) { Text("Skip") }
                }
            } else {
                Text((if (p.decision == true) "✓ " else "✗ skipped: ") + p.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CommandList(catalog: CommandCatalog?, onRun: (CommandAction) -> Unit) {
    if (catalog == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (catalog.actions.isEmpty()) {
        Text("No commands set up on the Mac yet (Idroid → Settings → Commands).", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        catalog.actions.groupBy { it.group.ifBlank { "Other" } }.forEach { (group, actions) ->
            item { Text(group, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(actions, key = { it.id }) { action ->
                ListItem(
                    modifier = Modifier.clickable { onRun(action) },
                    headlineContent = { Text(action.name) },
                    supportingContent = { Text(action.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsDialog(
    initial: AiSettings,
    keyFor: (AiProvider) -> String,
    modelFor: (AiProvider) -> String,
    onDismiss: () -> Unit,
    onSave: (AiSettings) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var models by remember { mutableStateOf<List<AiModel>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // Fetch the provider's models whenever the key or provider changes (debounced while typing).
    LaunchedEffect(s.provider, s.apiKey) {
        models = null
        loadError = null
        if (s.apiKey.isBlank()) return@LaunchedEffect
        delay(600)
        loading = true
        runCatching { listModels(s.provider, s.apiKey.trim()) }
            .onSuccess { list ->
                models = list
                if (list.isEmpty()) loadError = "No usable models for this key"
            }
            .onFailure { loadError = it.message ?: "Couldn't list models" }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AiProvider.entries.forEachIndexed { i, provider ->
                        SegmentedButton(
                            selected = s.provider == provider,
                            onClick = { s = s.copy(provider = provider, model = modelFor(provider), apiKey = keyFor(provider)) },
                            shape = SegmentedButtonDefaults.itemShape(i, AiProvider.entries.size),
                        ) { Text(provider.label, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(
                    s.apiKey, { s = s.copy(apiKey = it) }, label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { if (models != null) menuOpen = it }) {
                    OutlinedTextField(
                        value = models?.firstOrNull { it.id == s.model }?.label ?: s.model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        placeholder = { Text(if (s.apiKey.isBlank()) "Paste the key first" else "Choose a model") },
                        trailingIcon = {
                            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else ExposedDropdownMenuDefaults.TrailingIcon(menuOpen)
                        },
                        supportingText = loadError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        models.orEmpty().forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label)
                                        if (m.label != m.id) Text(m.id, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { s = s.copy(model = m.id); menuOpen = false },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run commands without asking")
                        Text("Off: you approve every command the AI wants to run.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(s.autoRun, { s = s.copy(autoRun = it) })
                }
                Text("The key stays encrypted on this phone and goes only to ${s.provider.label}. Command output is sent to it as context.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(s) }, enabled = s.apiKey.isNotBlank() && s.model.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

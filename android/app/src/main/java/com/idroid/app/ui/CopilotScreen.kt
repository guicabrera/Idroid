package com.idroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idroid.app.IdroidApplication
import com.idroid.app.data.COPILOT_GLOBAL
import com.idroid.app.data.CopilotMessage
import com.idroid.app.data.CopilotPermission
import com.idroid.app.data.CopilotState
import com.idroid.app.data.ExecRequest
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The bridge's JSON answer; anything else (e.g. the Mac has no Idroid Bridge) becomes a readable error. */
private fun String.jsonLine(): String =
    lines().lastOrNull { it.startsWith("{") }
        ?: throw IllegalStateException(trim().take(300).ifEmpty { "No answer from the Idroid Bridge plugin in Obsidian on the Mac" })

/** Ids from the bridge go on a command line only if they look like ids. */
private val BRIDGE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

/** The helper the Idroid Bridge plugin writes on the Mac: copilot <METHOD> <PATH> [JSON body as $1]. */
private const val HELPER = "\"\$HOME/.idroid/copilot\""

/**
 * Obsidian Copilot's agent chat on the phone (ADR-0006): the same live
 * conversations as the Copilot sidebar on the Mac, with its tools, permission
 * prompts and saved history. Talks to the Idroid Bridge plugin through the
 * command tunnel; long-polls so replies show up as they are written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotScreen(
    device: PeerDevice,
    pairing: PairingConfig,
    onBack: () -> Unit,
    active: Boolean = true,
    startNew: Boolean = false,
    chrome: Boolean = true,
) {
    BackHandler(enabled = active, onBack = onBack)
    val app = LocalContext.current.applicationContext as IdroidApplication
    val api = app.relayApiClient
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    var state by remember { mutableStateOf<CopilotState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    // Composing a new chat: show no conversation until the first message creates one
    // (otherwise the Mac's open chat would be adopted).
    var newChat by remember { mutableStateOf(startNew) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<com.idroid.app.data.CopilotSavedChat>?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var resuming by remember { mutableStateOf(false) }
    // Project picked on the phone (null until the first answer: then the Mac's project).
    var project by remember { mutableStateOf<String?>(null) }
    var projectMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    /** Runs the helper; [body] travels as "$1", never spliced into the command. */
    suspend fun call(method: String, path: String, body: String? = null): String {
        val out = StringBuilder()
        val command = "$HELPER $method '$path'" + if (body != null) " \"\$1\"" else ""
        val result = api.execCommand(pairing, device.id, ExecRequest(command = command, input = body)) { out.append(it) }
        if (result.exit != 0) throw IllegalStateException(out.toString().trim().ifEmpty { "bridge exited with ${result.exit}" })
        return out.toString().trim()
    }

    suspend fun post(path: String, vararg fields: Pair<String, String?>): String =
        call("POST", path, buildJsonObject { fields.forEach { (k, v) -> if (v != null) put(k, v) } }.toString())

    // Long-poll loop: each answer comes as soon as the chat changes (or after 25 s).
    LaunchedEffect(sessionId, reload) {
        var since = -1
        while (true) {
            val sid = sessionId?.takeIf { BRIDGE_ID.matches(it) }
            val path = "/state?since=$since&wait=" + (if (since < 0) 0 else 25) +
                (sid?.let { "&session=$it" } ?: if (newChat) "&session=new" else "")
            runCatching { json.decodeFromString<CopilotState>(call("GET", path).jsonLine()) }
                .onSuccess { s ->
                    error = null
                    state = s
                    since = s.version
                    if (project == null) project = s.project
                    if (sessionId == null && !newChat) sessionId = s.session?.id
                }
                .onFailure {
                    error = it.message
                    delay(3_000)
                }
        }
    }

    val chat = state?.session
    LaunchedEffect(chat?.messages?.size, chat?.messages?.lastOrNull()?.text?.length, chat?.permissions?.size) {
        val count = (chat?.messages?.size ?: 0) + (chat?.permissions?.size ?: 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    val busy = chat?.status == "running" || chat?.status == "awaiting_permission" || chat?.status == "starting"

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        sending = true
        scope.launch {
            runCatching { post("/send", "session" to sessionId, "text" to text, "project" to project) }
                .onSuccess { r ->
                    runCatching { json.decodeFromString<Map<String, String>>(r.jsonLine())["session"] }
                        .getOrNull()?.let { newChat = false; if (it != sessionId) sessionId = it else reload++ }
                }
                .onFailure { error = it.message; input = text }
            sending = false
        }
    }

    if (showHistory) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!resuming) showHistory = false },
            title = { Text("Saved Copilot chats") },
            text = {
                val list = history
                when {
                    list == null || resuming -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text("No saved agent chats.")
                    else -> LazyColumn {
                        items(list, key = { it.path }) { c ->
                            androidx.compose.material3.ListItem(
                                modifier = Modifier.clickable {
                                    resuming = true
                                    scope.launch {
                                        runCatching { post("/resume", "path" to c.path) }
                                            .onSuccess { r ->
                                                runCatching { json.decodeFromString<Map<String, String>>(r.jsonLine())["session"] }
                                                    .getOrNull()?.let { newChat = false; sessionId = it; reload++ }
                                                showHistory = false
                                            }
                                            .onFailure { error = it.message }
                                        resuming = false
                                    }
                                },
                                headlineContent = { Text(c.title, maxLines = 2) },
                                supportingContent = {
                                    Text(android.text.format.DateUtils.getRelativeTimeSpanString(c.lastUsed).toString(), fontSize = 12.sp)
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }, enabled = !resuming) { Text("Close") } },
        )
    }

    Scaffold(
        topBar = {
            if (chrome) TopAppBar(
                title = {
                    Box {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f, fill = false)) {
                                val projectName = when (project) {
                                    null, COPILOT_GLOBAL -> null
                                    else -> state?.projects?.firstOrNull { it.id == project }?.name
                                }
                                Text(projectName?.let { "Copilot · $it" } ?: "Copilot", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(
                                    state?.sessions?.firstOrNull { it.id == sessionId }?.title ?: "New chat",
                                    fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.ArrowDropDown, "Conversations") }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Saved chats…", color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuOpen = false
                                    showHistory = true
                                    history = null
                                    scope.launch {
                                        history = runCatching {
                                            json.decodeFromString<com.idroid.app.data.CopilotHistory>(call("GET", "/history" + (project?.takeIf { BRIDGE_ID.matches(it) }?.let { "?project=$it" } ?: "")).jsonLine()).chats
                                        }.getOrElse { error = it.message; emptyList() }
                                    }
                                },
                            )
                            state?.sessions.orEmpty().filter { project == null || it.project == project }.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(s.title, maxLines = 1)
                                            Text(s.status + if (s.active) " · open on the Mac" else "", fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { newChat = false; sessionId = s.id; menuOpen = false },
                                )
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { projectMenuOpen = true }) { Icon(Icons.Filled.Folder, "Project") }
                        DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                            val pick = { id: String ->
                                projectMenuOpen = false
                                if (id != project) {
                                    project = id
                                    // A chat belongs to one project: start fresh in the new one.
                                    newChat = true; sessionId = null; state = state?.copy(session = null); reload++
                                }
                            }
                            DropdownMenuItem(
                                text = { Text((if (project == COPILOT_GLOBAL) "✓ " else "") + "No project") },
                                onClick = { pick(COPILOT_GLOBAL) },
                            )
                            state?.projects.orEmpty().forEach { p ->
                                DropdownMenuItem(
                                    text = { Text((if (project == p.id) "✓ " else "") + p.name, maxLines = 1) },
                                    onClick = { pick(p.id) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { newChat = true; sessionId = null; state = state?.copy(session = null); reload++ }) {
                        Icon(Icons.Filled.Add, "New chat")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            error?.let {
                Text(
                    if (it.contains("isn't running")) "Open Obsidian on ${device.name}: the Idroid Bridge plugin runs inside it." else it,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state == null && error == null) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (state != null && chat == null) item {
                    Text("New conversation with Copilot on ${device.name}. It works in your vault with Copilot's tools, like the sidebar on the Mac.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(chat?.messages.orEmpty(), key = { it.id }) { MessageBubble(it) }
                items(chat?.permissions.orEmpty(), key = { it.toolCallId }) { p ->
                    PermissionCard(p) { option ->
                        scope.launch {
                            runCatching { post("/permission", "session" to chat?.id, "toolCallId" to p.toolCallId, "optionId" to option) }
                                .onFailure { error = it.message }
                        }
                    }
                }
                if (busy && chat?.permissions.isNullOrEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Copilot…") },
                    maxLines = 5,
                )
                if (busy) {
                    IconButton(onClick = { scope.launch { runCatching { post("/cancel", "session" to chat?.id) } } }) {
                        Icon(Icons.Filled.Stop, "Stop")
                    }
                }
                IconButton(onClick = ::send, enabled = input.isNotBlank() && !sending) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: CopilotMessage) {
    val mine = m.sender == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 340.dp)
                .background(
                    when {
                        m.error -> MaterialTheme.colorScheme.errorContainer
                        mine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            m.tools.forEach { t ->
                Text(
                    (if (t.status == "completed") "✓ " else if (t.status == "failed") "✗ " else "… ") + t.title +
                        (if (t.detail.isNotBlank()) " · ${t.detail}" else ""),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (m.text.isNotBlank()) SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PermissionCard(p: CopilotPermission, onChoose: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Copilot wants to use ${p.title}", style = MaterialTheme.typography.titleSmall)
            if (p.detail.isNotBlank()) Text(p.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEach { o ->
                    if (o.kind.startsWith("allow")) Button(onClick = { onChoose(o.id) }) { Text(o.name, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { onChoose(o.id) }) { Text(o.name, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.size(0.dp))
        }
    }
}

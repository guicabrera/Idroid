package com.idroid.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A command the Mac lets paired devices run by name (ADR-0005). */
@Serializable
data class CommandAction(
    val id: String,
    val name: String,
    val command: String,
    @SerialName("takes_input") val takesInput: Boolean = false,
    val group: String = "",
)

@Serializable
data class CommandCatalog(
    @SerialName("terminal_enabled") val terminalEnabled: Boolean = false,
    val home: String = "~",
    val actions: List<CommandAction> = emptyList(),
    @SerialName("ssh_host") val sshHost: String = "",
    val user: String = "",
    @SerialName("remote_login") val remoteLogin: Boolean = false,
    val vault: String? = null,
)

/** A Claude Code conversation in the vault, including those the Obsidian Copilot started. */
@Serializable
data class ClaudeConversation(val id: String, val title: String, val modified: Long)

@Serializable
data class ExecRequest(
    val command: String? = null,
    @SerialName("action_id") val actionId: String? = null,
    val input: String? = null,
    val cwd: String? = null,
)

@Serializable
data class ExecResult(val exit: Int, val cwd: String = "")

@Serializable
data class CompleteRequest(val line: String, val cwd: String?)

@Serializable
data class CompleteResponse(val suggestions: List<String> = emptyList())

// --- Copilot bridge (ADR-0006): Obsidian Copilot's agent chat, through the Idroid Bridge plugin ---

@Serializable
data class CopilotState(val version: Int = 0, val sessions: List<CopilotSession> = emptyList(), val session: CopilotChat? = null)

@Serializable
data class CopilotSession(val id: String, val title: String, val status: String, val backend: String = "", val active: Boolean = false)

@Serializable
data class CopilotChat(
    val id: String,
    val status: String,
    val messages: List<CopilotMessage> = emptyList(),
    val permissions: List<CopilotPermission> = emptyList(),
)

@Serializable
data class CopilotMessage(val id: String, val sender: String, val text: String = "", val tools: List<CopilotTool> = emptyList(), val error: Boolean = false)

@Serializable
data class CopilotTool(val title: String, val status: String = "", val detail: String = "")

@Serializable
data class CopilotPermission(val toolCallId: String, val title: String, val detail: String = "", val options: List<CopilotOption> = emptyList())

@Serializable
data class CopilotOption(val id: String, val name: String, val kind: String = "")

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
)

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

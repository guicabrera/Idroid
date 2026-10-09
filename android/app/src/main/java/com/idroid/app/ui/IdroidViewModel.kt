package com.idroid.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idroid.app.IdroidApplication
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.PairingConfig
import com.idroid.app.data.PeerDevice
import com.idroid.app.data.RelayException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a send goes: one device (by id), or every device in the group when null. */
typealias SendTarget = String?

data class HomeUiState(
    val pairing: PairingConfig? = null,
    val me: PeerDevice? = null,
    val devices: List<PeerDevice> = emptyList(),
    val inbox: List<ItemMeta> = emptyList(),
    val isRefreshing: Boolean = false,
    val isConnected: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
) {
    fun deviceName(id: String): String = devices.firstOrNull { it.id == id }?.name ?: "Unknown device"
}

class IdroidViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IdroidApplication
    private val pairingRepository = app.pairingRepository
    private val relayApiClient = app.relayApiClient

    private val _uiState = MutableStateFlow(HomeUiState(pairing = pairingRepository.load()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            app.relayConnected.collect { connected -> _uiState.update { it.copy(isConnected = connected) } }
        }
        viewModelScope.launch {
            app.relayEvents.collect { event ->
                when (event) {
                    "new_item" -> refreshInbox()
                    "devices_changed" -> refreshDevices()
                }
            }
        }
    }

    fun savePairing(config: PairingConfig) {
        pairingRepository.save(config)
        _uiState.update { it.copy(pairing = config, errorMessage = null) }
        refresh()
    }

    fun forgetPairing() {
        pairingRepository.clear()
        _uiState.value = HomeUiState(pairing = null)
    }

    /** Unpairs this phone from every device, then forgets the pairing locally. */
    fun leave(onDone: () -> Unit) {
        val state = _uiState.value
        val pairing = state.pairing ?: return
        viewModelScope.launch {
            state.me?.let { me -> runCatching { relayApiClient.removeDevice(pairing, me.id) } }
            forgetPairing()
            onDone()
        }
    }

    fun refresh() {
        refreshDevices()
        refreshInbox()
    }

    fun refreshDevices() {
        val pairing = _uiState.value.pairing ?: return
        viewModelScope.launch {
            runCatching { relayApiClient.listDevices(pairing) }
                .onSuccess { response -> _uiState.update { it.copy(me = response.me, devices = response.devices) } }
                .onFailure { e ->
                    // This phone was removed from another device: back to pairing.
                    if (e is RelayException && e.httpCode == 401) forgetPairing() else showError(e)
                }
        }
    }

    fun removeDevice(device: PeerDevice) {
        val pairing = _uiState.value.pairing ?: return
        viewModelScope.launch {
            runCatching { relayApiClient.removeDevice(pairing, device.id) }
                .onSuccess {
                    showStatus("Removed ${device.name}")
                    refreshDevices()
                }
                .onFailure(::showError)
        }
    }

    fun refreshInbox() {
        val pairing = _uiState.value.pairing ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching { relayApiClient.listInbox(pairing) }
                .onSuccess { items ->
                    // Clipboard pushes (ADR-0002) are applied and deleted by
                    // RelaySyncService as they arrive, not shown as inbox entries.
                    val visible = items.filter { it.kind != ItemKind.CLIPBOARD }.sortedByDescending { it.created_at }
                    _uiState.update { it.copy(inbox = visible, isRefreshing = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isRefreshing = false) }
                    showError(e)
                }
        }
    }

    fun deleteItem(itemId: String) {
        val pairing = _uiState.value.pairing ?: return
        _uiState.update { state -> state.copy(inbox = state.inbox.filterNot { it.id == itemId }) }
        viewModelScope.launch {
            runCatching { relayApiClient.deleteItem(pairing, itemId) }
                .onFailure { e ->
                    showError(e)
                    refreshInbox()
                }
        }
    }

    fun sendText(text: String, target: SendTarget) {
        send(target, "text") { pairing, recipient -> relayApiClient.uploadText(pairing, recipient, ItemKind.TEXT, text) }
    }

    fun sendUris(uris: List<Uri>, target: SendTarget) {
        if (uris.isEmpty()) return
        val what = if (uris.size == 1) "file" else "${uris.size} files"
        send(target, what) { pairing, recipient ->
            for (uri in uris) {
                relayApiClient.uploadUri(app, pairing, recipient, uri, app.contentResolver.getType(uri))
            }
        }
    }

    private fun send(target: SendTarget, what: String, upload: suspend (PairingConfig, String) -> Unit) {
        val state = _uiState.value
        val pairing = state.pairing ?: return
        val recipients = if (target == null) state.devices else state.devices.filter { it.id == target }
        if (recipients.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No device to send to — add one first") }
            return
        }
        viewModelScope.launch {
            runCatching { recipients.forEach { upload(pairing, it.id) } }
                .onSuccess {
                    val to = if (recipients.size == 1) recipients[0].name else "all devices"
                    showStatus("Sent $what to $to")
                }
                .onFailure(::showError)
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    private fun showStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message, errorMessage = null) }
    }

    private fun showError(e: Throwable) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Something went wrong", statusMessage = null) }
    }
}

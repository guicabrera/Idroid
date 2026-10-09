package com.idroid.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idroid.app.IdroidApplication
import com.idroid.app.data.ItemKind
import com.idroid.app.data.ItemMeta
import com.idroid.app.data.PairingConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val pairing: PairingConfig? = null,
    val inbox: List<ItemMeta> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class IdroidViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IdroidApplication
    private val pairingRepository = app.pairingRepository
    private val relayApiClient = app.relayApiClient

    private val _uiState = MutableStateFlow(HomeUiState(pairing = pairingRepository.load()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.pairing != null) refreshInbox()
    }

    fun savePairing(config: PairingConfig) {
        pairingRepository.save(config)
        _uiState.value = _uiState.value.copy(pairing = config, errorMessage = null)
        refreshInbox()
    }

    fun forgetPairing() {
        pairingRepository.clear()
        _uiState.value = HomeUiState(pairing = null)
    }

    fun refreshInbox() {
        val pairing = _uiState.value.pairing ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            runCatching { relayApiClient.listInbox(pairing) }
                .onSuccess { items ->
                    // Clipboard pushes (ADR-0002) are applied and deleted by
                    // RelaySyncService as they arrive, not shown as inbox entries.
                    val visible = items.filter { it.kind != ItemKind.CLIPBOARD }
                    _uiState.value = _uiState.value.copy(inbox = visible, isRefreshing = false)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isRefreshing = false, errorMessage = e.message) }
        }
    }

    fun deleteItem(itemId: String) {
        val pairing = _uiState.value.pairing ?: return
        viewModelScope.launch {
            runCatching { relayApiClient.deleteItem(pairing, itemId) }
                .onSuccess { refreshInbox() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(errorMessage = e.message) }
        }
    }
}

package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.InstalledAddonRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddonSettingsUiState(
    val url: String = "",
    val installed: List<InstalledAddonRow> = emptyList(),
    val isBusy: Boolean = false,
    /** U5: addon ids with recorded capability-disclosure consent. */
    val consentedIds: Set<String> = emptySet(),
)

class AddonSettingsViewModel(
    private val registry: AddonRegistryLike,
    private val onError: (message: String) -> Unit = {},
    private val hasConsent: (addonId: String) -> Boolean = { false },
    private val onConsentChange: (addonId: String, granted: Boolean) -> Unit = { _, _ -> },
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddonSettingsUiState())
    val uiState: StateFlow<AddonSettingsUiState> = _uiState.asStateFlow()

    fun onUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(url = value)
    }

    fun refresh() {
        viewModelScope.launch {
            val installed = registry.listInstalled()
            _uiState.value =
                _uiState.value.copy(
                    installed = installed,
                    consentedIds = installed.map { it.id }.filter(hasConsent).toSet(),
                )
        }
    }

    fun install() {
        val target = _uiState.value.url.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            try {
                registry.install(target)
                _uiState.value = _uiState.value.copy(url = "", installed = registry.listInstalled())
            } catch (err: Throwable) {
                onError(err.message ?: err.javaClass.simpleName)
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    fun toggle(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            try {
                registry.setEnabled(id, enabled)
                _uiState.value = _uiState.value.copy(installed = registry.listInstalled())
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            try {
                registry.uninstall(id)
                _uiState.value = _uiState.value.copy(installed = registry.listInstalled())
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    /**
     * U5: records or revokes capability-disclosure consent for [id], then
     * refreshes the consented set. Persisted durably by the caller's store.
     */
    fun setConsent(
        id: String,
        granted: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            try {
                onConsentChange(id, granted)
                val installed = registry.listInstalled()
                _uiState.value =
                    _uiState.value.copy(
                        installed = installed,
                        consentedIds = installed.map { it.id }.filter(hasConsent).toSet(),
                    )
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }
}

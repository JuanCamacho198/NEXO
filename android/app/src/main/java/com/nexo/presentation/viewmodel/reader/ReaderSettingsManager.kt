package com.nexo.presentation.viewmodel.reader

import com.nexo.data.session.ReaderPreferences
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.ReaderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderSettingsState(
    val readerSettings: ReaderSettings = ReaderSettings(),
    val showSplitSettings: Boolean = false,
)

/**
 * Settings slice of the reader facade split (SDD reader-facade-split, slice 2).
 * Single owner: only [ReaderSettingsManager] holds the MutableStateFlow and
 * mutating funs; the VM re-exports it as `settingsUiState`.
 */
typealias SettingsUiState = ReaderSettingsState

class ReaderSettingsManager(
    private val readerPreferences: ReaderPreferences?,
) {
    private val _state = MutableStateFlow(ReaderSettingsState())
    val state: StateFlow<ReaderSettingsState> = _state.asStateFlow()

    init {
        // Load persisted settings on creation
        readerPreferences?.load()?.let { settings ->
            _state.update { it.copy(readerSettings = settings) }
        }
    }

    fun updateReaderSettings(settings: ReaderSettings) {
        readerPreferences?.save(settings)
        _state.update { it.copy(readerSettings = settings) }
    }

    fun onToggleSplitSettings() {
        _state.update { it.copy(showSplitSettings = !it.showSplitSettings) }
    }

    fun onUpdateCustomHighlightColor(
        index: Int,
        hex: String,
    ) {
        _state.update { state ->
            val current =
                state.readerSettings.customHighlightColors
                    ?: HighlightColor.defaultHexList()
            val colors = current.toMutableList()
            if (index in colors.indices) {
                colors[index] = hex
                val updated = state.readerSettings.copy(customHighlightColors = colors)
                readerPreferences?.save(updated)
                state.copy(readerSettings = updated)
            } else {
                state
            }
        }
    }

    fun onAddCustomHighlightColor() {
        _state.update { state ->
            val current =
                state.readerSettings.customHighlightColors
                    ?: HighlightColor.defaultHexList()
            if (current.size >= 5) return@update state
            val colors = current.toMutableList()
            colors.add(HighlightColor.YELLOW.hex)
            val updated = state.readerSettings.copy(customHighlightColors = colors)
            readerPreferences?.save(updated)
            state.copy(readerSettings = updated)
        }
    }

    fun onDeleteCustomHighlightColor(index: Int) {
        _state.update { state ->
            val current =
                state.readerSettings.customHighlightColors
                    ?: HighlightColor.defaultHexList()
            if (current.size <= 3) return@update state
            val colors = current.toMutableList()
            colors.removeAt(index)
            val updated = state.readerSettings.copy(customHighlightColors = colors)
            readerPreferences?.save(updated)
            state.copy(readerSettings = updated)
        }
    }

    fun onResetCustomHighlightColors() {
        _state.update { state ->
            val default = ReaderSettings()
            val updated = state.readerSettings.copy(customHighlightColors = default.customHighlightColors)
            readerPreferences?.save(updated)
            state.copy(readerSettings = updated)
        }
    }
}

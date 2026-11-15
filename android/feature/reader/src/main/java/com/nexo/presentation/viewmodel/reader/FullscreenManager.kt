package com.nexo.presentation.viewmodel.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FullscreenState(
    val isFullscreen: Boolean = false,
)

/**
 * Chrome slice of the reader facade split (SDD reader-facade-split, slice 2).
 * Single owner: only [FullscreenManager] holds the MutableStateFlow and
 * mutating funs; the VM re-exports it as `chromeUiState`.
 */
typealias ChromeUiState = FullscreenState

class FullscreenManager {
    private val _state = MutableStateFlow(FullscreenState())
    val state: StateFlow<FullscreenState> = _state.asStateFlow()

    fun onToggleFullscreen() {
        _state.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    /**
     * Idempotently enters fullscreen (immersive) mode. Used to auto-enter
     * the reader's immersive reading mode when a book is opened.
     */
    fun enterFullscreen() {
        _state.update { it.copy(isFullscreen = true) }
    }

    fun reset() {
        _state.value = FullscreenState()
    }
}

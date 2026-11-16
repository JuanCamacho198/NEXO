package com.nexo.domain.update

/**
 * UI-facing state of an update check.
 *
 * Lives in the domain so a feature module can render it without depending on
 * `:app`, where the ViewModel that produces it stays.
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data object UpToDate : UpdateUiState

    data class Available(
        val candidate: UpdateCandidate,
    ) : UpdateUiState

    data class Error(
        val kind: UpdateErrorKind,
    ) : UpdateUiState
}

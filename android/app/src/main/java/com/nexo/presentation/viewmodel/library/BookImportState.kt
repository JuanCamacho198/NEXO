package com.nexo.presentation.viewmodel.library

/**
 * UI-level import state for the library.
 * Managed by [BookImportStateHolder].
 *
 * Transitions: Idle → Extracting → Analyzing → Saving → Idle.
 */
sealed interface BookImportState {
    /** No import in progress. */
    data object Idle : BookImportState

    /** Copying / extracting the file. */
    data class Extracting(
        val progress: Float = 0f,
    ) : BookImportState

    /** Analyzing metadata and content. */
    data class Analyzing(
        val progress: Float = 0f,
    ) : BookImportState

    /** Saving to the local library. */
    data class Saving(
        val progress: Float = 0f,
    ) : BookImportState
}

/** Convenience property for backward-compat with [LibraryUiState.isImporting]. */
val BookImportState.isImporting: Boolean get() = this !is BookImportState.Idle

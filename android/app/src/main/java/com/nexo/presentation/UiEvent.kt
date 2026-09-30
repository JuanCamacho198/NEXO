package com.nexo.presentation

sealed interface UiEvent {
    data class ShowSnackbar(
        val message: String,
    ) : UiEvent

    data class ShowToast(
        val message: String,
    ) : UiEvent

    /** Emitted when the user taps Share in the context menu.
     *  [ReaderScreen] handles the intent launch. */
    data class ShareText(
        val text: String,
    ) : UiEvent

    /** Emitted to share a book file (EPUB/PDF) via ACTION_SEND.
     *  The host resolves the FileProvider URI and launches the chooser. */
    data class ShareFile(
        val filePath: String,
        val mimeType: String,
    ) : UiEvent

    data class CopyToClipboard(
        val text: String,
    ) : UiEvent

    data class OpenBookAtLocation(
        val bookId: String,
        val cfiRange: String,
    ) : UiEvent
}

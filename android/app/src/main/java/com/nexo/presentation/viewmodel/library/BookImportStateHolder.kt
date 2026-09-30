package com.nexo.presentation.viewmodel.library

import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.presentation.viewmodel.LibraryImportEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Manages book import state for the library.
 *
 * Handles EPUB and PDF import flows, transitioning through
 * [BookImportState.Extracting] → [BookImportState.Analyzing] →
 * [BookImportState.Saving] → [BookImportState.Idle], and emitting
 * success/failure events via the [onImportEvent] callback.
 *
 * @param importEpubBookUseCase  Use case for importing EPUB books
 * @param libraryRepository     Repository for PDF import and general book ops
 * @param scope                 CoroutineScope for import jobs (e.g. viewModelScope)
 * @param onImportEvent         Callback to emit [LibraryImportEvent] (success/failure)
 * @param mainDispatcher        Dispatcher for UI-side state updates (default: [Dispatchers.Main])
 * @param onStateChanged        Callback invoked synchronously after every state mutation
 */
class BookImportStateHolder(
    private val importEpubBookUseCase: ImportEpubBookUseCase,
    private val libraryRepository: LibraryRepository,
    private val scope: CoroutineScope,
    private val onImportEvent: (LibraryImportEvent) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onStateChanged: (BookImportState) -> Unit = {},
) {
    private val _state = MutableStateFlow<BookImportState>(BookImportState.Idle)
    val state: StateFlow<BookImportState> = _state.asStateFlow()

    private companion object {
        const val EXTRACTING_PROGRESS = 0.3f
        const val ANALYZING_PROGRESS = 0.6f
        const val SAVING_PROGRESS = 0.9f
        const val STAGE_DELAY_MS = 200L
    }

    init {
        onStateChanged(_state.value)
    }

    /**
     * Import an EPUB book from a content URI.
     * Transitions: Extracting → Analyzing → Saving → Idle, then emits success/failure.
     */
    fun importBookFromEpub(
        sourcePath: String,
        fallbackTitle: String?,
        inputStreamProvider: suspend () -> InputStream?,
    ) {
        _state.update { BookImportState.Extracting(EXTRACTING_PROGRESS) }
        onStateChanged(_state.value)

        scope.launch(mainDispatcher) {
            try {
                delay(STAGE_DELAY_MS)
                _state.update { BookImportState.Analyzing(ANALYZING_PROGRESS) }
                onStateChanged(_state.value)

                delay(STAGE_DELAY_MS)
                _state.update { BookImportState.Saving(SAVING_PROGRESS) }
                onStateChanged(_state.value)

                val result =
                    importEpubBookUseCase(
                        request =
                            BookImportRequest(
                                sourcePath = sourcePath,
                                fallbackTitle = fallbackTitle,
                            ),
                        inputStreamProvider = inputStreamProvider,
                    )

                result.fold(
                    onSuccess = { book ->
                        onImportEvent(LibraryImportEvent.Success(book.title))
                    },
                    onFailure = { error ->
                        onImportEvent(
                            LibraryImportEvent.Failure(
                                error.message ?: "Failed to import EPUB",
                            ),
                        )
                    },
                )
            } finally {
                // Always return to Idle, even when the use case or the input
                // stream provider throws — a stuck non-Idle state would leave
                // the import overlay swallowing every tap.
                _state.update { BookImportState.Idle }
                onStateChanged(_state.value)
            }
        }
    }

    /**
     * Import a PDF book from a local file.
     * Transitions: Extracting → Analyzing → Saving → Idle, then emits success/failure.
     */
    fun importPdfBook(
        sourcePath: String,
        fallbackTitle: String?,
        pdfFile: File,
    ) {
        _state.update { BookImportState.Extracting(EXTRACTING_PROGRESS) }
        onStateChanged(_state.value)

        scope.launch(mainDispatcher) {
            try {
                delay(STAGE_DELAY_MS)
                _state.update { BookImportState.Analyzing(ANALYZING_PROGRESS) }
                onStateChanged(_state.value)

                delay(STAGE_DELAY_MS)
                _state.update { BookImportState.Saving(SAVING_PROGRESS) }
                onStateChanged(_state.value)

                val result =
                    libraryRepository.importBookFromPdf(
                        request =
                            BookImportRequest(
                                sourcePath = sourcePath,
                                fallbackTitle = fallbackTitle,
                            ),
                        file = pdfFile,
                    )

                result.fold(
                    onSuccess = { book ->
                        onImportEvent(LibraryImportEvent.Success(book.title))
                    },
                    onFailure = { error ->
                        onImportEvent(
                            LibraryImportEvent.Failure(
                                error.message ?: "Failed to import PDF",
                            ),
                        )
                    },
                )
            } finally {
                // Always return to Idle, even when the repository or the file
                // stream throws — a stuck non-Idle state would leave the
                // import overlay swallowing every tap.
                _state.update { BookImportState.Idle }
                onStateChanged(_state.value)
            }
        }
    }

    /**
     * Force-resets the import state to [BookImportState.Idle].
     *
     * Defense-in-depth used by the NavHost overlay watchdog: guarantees a
     * stuck non-Idle state (e.g. a hung import job) can never keep the
     * import overlay blocking input indefinitely.
     */
    fun resetImportState() {
        _state.update { BookImportState.Idle }
        onStateChanged(_state.value)
    }
}

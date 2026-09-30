package com.nexo.presentation.viewmodel.library

import android.content.Context
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookStatus
import com.nexo.domain.repository.LibraryRepository
import com.nexo.presentation.UiEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages book action state for the library: delete, share, and status changes
 * (mark completed / plan to read).
 *
 * Each action follows the pattern: set state → perform async work →
 * clear state → emit snackbar/share event via [onUiEvent].
 *
 * Edit-metadata state is NOT part of this holder anymore: the full-screen
 * editor (`EditBookMetadataViewModel` + `book_edit/{bookId}`) replaced the
 * dialog (REQ-edit-screen-1, design D3).
 *
 * @param libraryRepository  Repository for book CRUD operations
 * @param appContext         Application context for string resources
 * @param scope              CoroutineScope for async operations (e.g. viewModelScope)
 * @param onUiEvent          Callback to emit [UiEvent] (snackbar, share intent)
 * @param mainDispatcher     Dispatcher for UI-side state updates (default: [Dispatchers.Main])
 * @param onStateChanged     Callback invoked synchronously after every state mutation
 */
class BookActionStateHolder(
    private val libraryRepository: LibraryRepository,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val onUiEvent: (UiEvent) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onStateChanged: (BookActionState) -> Unit = {},
) {
    private val _state = MutableStateFlow(BookActionState())
    val state: StateFlow<BookActionState> = _state.asStateFlow()

    init {
        onStateChanged(_state.value)
    }

    // ── Delete ──────────────────────────────────────────────────────────

    /** Open the delete confirmation dialog for the given book. */
    fun requestDeleteBook(book: Book) {
        _state.update { it.copy(bookToDelete = book) }
        onStateChanged(_state.value)
    }

    /** Dismiss the delete confirmation dialog without deleting. */
    fun dismissDeleteDialog() {
        _state.update { it.copy(bookToDelete = null) }
        onStateChanged(_state.value)
    }

    /** Confirm and execute the delete. Emits snackbar on success/failure. */
    fun confirmDeleteBook() {
        // Backwards compat — defaults to local+Drive (full delete)
        confirmDeleteLocalAndDrive()
    }

    /** Local-only delete: mirrors desktop handleHideBook — Drive file is kept. */
    fun confirmDeleteLocalOnly() {
        val book = _state.value.bookToDelete ?: return

        scope.launch(mainDispatcher) {
            val result = libraryRepository.deleteBookLocalOnly(book.id)
            _state.update { it.copy(bookToDelete = null) }
            onStateChanged(_state.value)

            result.fold(
                onSuccess = {
                    onUiEvent(UiEvent.ShowSnackbar("Deleted \"${book.title}\""))
                },
                onFailure = { error ->
                    onUiEvent(UiEvent.ShowSnackbar(error.message ?: "Failed to delete book"))
                },
            )
        }
    }

    /** Local + Drive delete: trashes Drive file and tombstones catalog (desktop handleRemoveBookFromDrive). */
    fun confirmDeleteLocalAndDrive() {
        val book = _state.value.bookToDelete ?: return

        scope.launch(mainDispatcher) {
            val result = libraryRepository.deleteBook(book.id)
            _state.update { it.copy(bookToDelete = null) }
            onStateChanged(_state.value)

            result.fold(
                onSuccess = {
                    onUiEvent(UiEvent.ShowSnackbar("Deleted \"${book.title}\""))
                },
                onFailure = { error ->
                    onUiEvent(UiEvent.ShowSnackbar(error.message ?: "Failed to delete book"))
                },
            )
        }
    }

    // ── Status changes ──────────────────────────────────────────────────

    /** Mark a book as completed. Emits snackbar. */
    fun onMenuMarkCompleted(book: Book) {
        updateBookStatus(book, BookStatus.COMPLETED, R.string.library_snackbar_marked_completed)
    }

    /** Mark a book as plan-to-read. Emits snackbar. */
    fun onMenuMarkPlanToRead(book: Book) {
        updateBookStatus(book, BookStatus.PLAN_TO_READ, R.string.library_snackbar_marked_plan_to_read)
    }

    private fun updateBookStatus(
        book: Book,
        status: String,
        successMessageRes: Int,
    ) {
        scope.launch(mainDispatcher) {
            val result = libraryRepository.updateBookStatus(book.id, status)
            result.fold(
                onSuccess = {
                    onUiEvent(UiEvent.ShowSnackbar(appContext.getString(successMessageRes)))
                },
                onFailure = { error ->
                    onUiEvent(UiEvent.ShowSnackbar(error.message ?: "Failed to update status"))
                },
            )
        }
    }

    // ── Share ───────────────────────────────────────────────────────────

    /** Share a book file. Sets [bookToShare], emits share intent, then clears. */
    fun onMenuShare(book: Book) {
        _state.update { it.copy(bookToShare = book) }
        onStateChanged(_state.value)

        val mimeType =
            when (book.format.lowercase()) {
                "pdf" -> "application/pdf"
                "epub" -> "application/epub+zip"
                else -> "*/*"
            }
        scope.launch(mainDispatcher) {
            onUiEvent(UiEvent.ShareFile(filePath = book.filePath, mimeType = mimeType))
            _state.update { it.copy(bookToShare = null) }
            onStateChanged(_state.value)
        }
    }
}

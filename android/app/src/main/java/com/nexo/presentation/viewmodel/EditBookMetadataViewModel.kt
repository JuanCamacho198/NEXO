package com.nexo.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.R
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.repository.LibraryRepository
import com.nexo.presentation.UiEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Maximum number of genre values the editor accepts (REQ-data-model-8). */
internal const val MAX_GENRES = 5

/** Maximum number of tag values the editor accepts (REQ-data-model-8). */
internal const val MAX_TAGS = 10

/**
 * Parses a comma-separated metadata field (genre/tags stored as TEXT, design D2)
 * into its individual values, trimmed and with blanks dropped.
 */
internal fun parseChipList(value: String?): List<String> =
    value
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

/**
 * Sanitizes a single user-entered chip value: strips embedded commas, trims,
 * and rejects values that become empty (REQ-data-model-8).
 */
internal fun sanitizeChipValue(value: String): String? = value.replace(",", "").trim().takeIf { it.isNotEmpty() }

/**
 * Sanitizes a whole chip list: strips commas, trims, dedupes case-insensitively,
 * and caps at [max] (REQ-data-model-8 — "input con coma se normaliza; duplicados
 * se colapsan"; UI limits 5 genres / 10 tags).
 */
internal fun sanitizeChipList(
    values: List<String>,
    max: Int,
): List<String> =
    values
        .mapNotNull { sanitizeChipValue(it) }
        .distinctBy { it.lowercase() }
        .take(max)

/**
 * Form state for the full-screen edit-metadata editor (design A9ymv).
 *
 * [genres] and [tags] are held as lists while editing; they are serialized to
 * comma-separated TEXT on save (the encoding used by [Book.genre]/[Book.tags]).
 */
data class EditBookMetadataUiState(
    val book: Book? = null,
    val isLoading: Boolean = true,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val publisher: String = "",
    val publishedDate: String? = null,
    val coverUri: Uri? = null,
    val coverBytes: ByteArray? = null,
    val isSaving: Boolean = false,
)

/**
 * Loads a book by id, exposes an editable form derived from it, sanitizes
 * genre/tag input (commas stripped, trim, dedupe, caps 5/10), and persists on
 * save via [LibraryRepository.updateBookMetadata] — saving the cover first when
 * a new one was picked, emitting a snackbar and invoking [onSaved] on success.
 *
 * The legacy edit-dialog logic moved here (design D3 — the dialog, its
 * `LibraryDialogs` branch, and the `BookActionStateHolder` edit state are
 * removed; this ViewModel is their replacement).
 */
class EditBookMetadataViewModel(
    private val bookId: String,
    private val libraryRepository: LibraryRepository,
    private val coverStorage: CoverStorage,
    private val appContext: Context,
    private val onSaved: () -> Unit = {},
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditBookMetadataUiState())
    val uiState: StateFlow<EditBookMetadataUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)

    /** One-shot UI events (snackbars) emitted by save. */
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    /** `true` once the form has been seeded from the loaded book. */
    private var seeded = false

    init {
        viewModelScope.launch {
            libraryRepository.observeBookById(bookId).collect { book ->
                if (book == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@collect
                }
                if (!seeded) {
                    seeded = true
                    _uiState.update {
                        it.copy(
                            book = book,
                            isLoading = false,
                            title = book.title,
                            author = book.author.orEmpty(),
                            description = book.description.orEmpty(),
                            genres = sanitizeChipList(parseChipList(book.genre), MAX_GENRES),
                            tags = sanitizeChipList(parseChipList(book.tags), MAX_TAGS),
                            language = book.language,
                            publisher = book.publisher.orEmpty(),
                            publishedDate = book.publishedDate,
                        )
                    }
                } else {
                    _uiState.update { it.copy(book = book, isLoading = false) }
                }
            }
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }

    fun onAuthorChange(value: String) = _uiState.update { it.copy(author = value) }

    fun onDescriptionChange(value: String) = _uiState.update { it.copy(description = value) }

    fun onPublisherChange(value: String) = _uiState.update { it.copy(publisher = value) }

    fun onLanguageChange(value: String) = _uiState.update { it.copy(language = value) }

    fun onPublishedDateChange(value: String?) = _uiState.update { it.copy(publishedDate = value) }

    /** Registers a newly picked cover (preview uri + bytes read from the picker). */
    fun onCoverSelected(
        uri: Uri?,
        bytes: ByteArray?,
    ) = _uiState.update { it.copy(coverUri = uri, coverBytes = bytes) }

    /** Adds a genre value after sanitization; ignores duplicates and over-cap input. */
    fun onGenreAdd(value: String) {
        val clean = sanitizeChipValue(value) ?: return
        val genres = _uiState.value.genres
        if (genres.size >= MAX_GENRES) return
        if (genres.any { it.equals(clean, ignoreCase = true) }) return
        _uiState.update { it.copy(genres = genres + clean) }
    }

    /** Removes the given genre value, if present. */
    fun onGenreRemove(value: String) = _uiState.update { it.copy(genres = it.genres.filterNot { v -> v.equals(value, ignoreCase = true) }) }

    /** Adds a tag value after sanitization; ignores duplicates and over-cap input. */
    fun onTagAdd(value: String) {
        val clean = sanitizeChipValue(value) ?: return
        val tags = _uiState.value.tags
        if (tags.size >= MAX_TAGS) return
        if (tags.any { it.equals(clean, ignoreCase = true) }) return
        _uiState.update { it.copy(tags = tags + clean) }
    }

    /** Removes the given tag value, if present. */
    fun onTagRemove(value: String) = _uiState.update { it.copy(tags = it.tags.filterNot { v -> v.equals(value, ignoreCase = true) }) }

    /**
     * Persists the form: saves a new cover (when picked), updates metadata via
     * [LibraryRepository.updateBookMetadata] (which also queues the outbox
     * UPDATE, REQ-data-model-6), emits a success/error snackbar, and calls
     * [onSaved] on success so the host navigates back.
     */
    fun save() {
        val current = _uiState.value
        val book = current.book ?: return
        if (current.isSaving) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(mainDispatcher) {
            val coverPath =
                current.coverBytes?.let { bytes ->
                    coverStorage.saveCover(bookId = book.id, coverBytes = bytes).getOrNull()
                } ?: book.coverPath

            val result =
                libraryRepository.updateBookMetadata(
                    bookId = book.id,
                    title = current.title.trim().ifBlank { book.title },
                    author = current.author.trim().ifBlank { null },
                    description = current.description.trim().ifBlank { null },
                    coverPath = coverPath,
                    genre = current.genres.joinToString(", ").ifBlank { null },
                    language = current.language?.trim()?.ifBlank { null },
                    publisher = current.publisher.trim().ifBlank { null },
                    tags = current.tags.joinToString(", ").ifBlank { null },
                    publishedDate = current.publishedDate,
                )

            _uiState.update { it.copy(isSaving = false) }

            result.fold(
                onSuccess = {
                    _uiEvent.tryEmit(
                        UiEvent.ShowSnackbar(appContext.getString(R.string.library_snackbar_metadata_saved)),
                    )
                    onSaved()
                },
                onFailure = { error ->
                    _uiEvent.tryEmit(
                        UiEvent.ShowSnackbar(
                            error.message ?: appContext.getString(R.string.edit_metadata_save_failed),
                        ),
                    )
                },
            )
        }
    }

    class Factory(
        private val bookId: String,
        private val libraryRepository: LibraryRepository,
        private val coverStorage: CoverStorage,
        private val appContext: Context,
        private val onSaved: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EditBookMetadataViewModel(
                bookId = bookId,
                libraryRepository = libraryRepository,
                coverStorage = coverStorage,
                appContext = appContext,
                onSaved = onSaved,
            ) as T
    }
}

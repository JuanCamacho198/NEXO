package com.nexo.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.UserBookRow
import com.nexo.data.remote.sync.DriveSyncState
import com.nexo.data.remote.sync.SyncService
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookStatus
import com.nexo.domain.model.effectiveStatus
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.library.BookActionStateHolder
import com.nexo.presentation.viewmodel.library.BookFilterStateHolder
import com.nexo.presentation.viewmodel.library.BookImportState
import com.nexo.presentation.viewmodel.library.BookImportStateHolder
import com.nexo.presentation.viewmodel.library.CatalogPollingHolder
import com.nexo.presentation.viewmodel.library.isImporting
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * LibraryUiState — UI state for the Library screen (bookshelf).
 *
 * **Used by**: LibraryScreen
 * **Mutated by**: [LibraryViewModel] (init block — Room/Sync observation),
 *                 the filter/import/action state holders via callbacks, and
 *                 direct updates from [LibraryViewModel.onPullToRefresh].
 *
 * @property books All books from the local library, unfiltered.
 * @property isLoading `true` until the first emission from the library flow lands.
 * @property isImporting `true` while an EPUB/PDF import is in progress.
 * @property bookToDelete The book targeted for deletion (drives the confirm dialog).
 * @property bookToShare The book targeted for share (drives the share intent).
 * @property totalMinutesRead Cumulative reading time across all books (minutes).
 * @property readingMinutesByBook Per-book reading time map (bookId → minutes) used for derived status.
 * @property isSyncing `true` while the [SyncService] is running.
 * @property isRefreshing `true` while a pull-to-refresh is in progress.
 * @property syncError Last sync error message, or `null`.
 * @property statusFilter Active status filter id: `"all" | "reading" | "pending" | "completed"`.
 * @property sortBy Active sort key: `"date_added" | "title" | "author" | "last_read"`.
 * @property isGridView `true` for grid layout, `false` for list.
 * @property searchQuery Current text in the search input (updates immediately).
 * @property debouncedSearchQuery Debounced (300ms) value of [searchQuery] used for filtering.
 * @property showSearch `true` when the search input is expanded.
 * @property showFilterSheet `true` when the filter bottom sheet is open.
 * @property filterFormat Format filter id: `"all" | <format token>`.
 */
data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val bookToDelete: Book? = null,
    val bookToShare: Book? = null,
    val totalMinutesRead: Long = 0L,
    val readingMinutesByBook: Map<String, Long> = emptyMap(),
    // Canonical progress (reading_progress.percentage wins, fallback cache) via GetBookProgressUseCase
    val progressPercentByBook: Map<String, Float> = emptyMap(),
    // ── Sync state ──
    val isSyncing: Boolean = false,
    val isRefreshing: Boolean = false,
    val syncError: String? = null,
    // ── Cross-device download ──
    val downloadableBooks: List<UserBookRow> = emptyList(),
    val downloadState: Map<String, DownloadState> = emptyMap(),
    val isDownloadableLoading: Boolean = true,
    // ── UI State (filters, sort, search) ──
    val statusFilter: String = "all",
    val sortBy: String = "date_added",
    val isGridView: Boolean = true,
    val searchQuery: String = "",
    val debouncedSearchQuery: String = "",
    val showSearch: Boolean = false,
    val showFilterSheet: Boolean = false,
    val filterFormat: String = "all",
) {
    companion object {
        /** Minutes of reading considered "completed". */
        const val READING_TARGET_MINUTES = 300L
    }
}

/**
 * Filters [books] by [statusFilter] using [readingMinutesByBook] for derived status.
 */
private fun filterBooks(
    books: List<Book>,
    statusFilter: String,
    readingMinutesByBook: Map<String, Long>,
): List<Book> =
    when (statusFilter) {
        "reading" ->
            books.filter {
                val eff = it.effectiveStatus(readingMinutesByBook[it.id] ?: 0L)
                eff == BookStatus.READING
            }
        "pending" ->
            books.filter {
                val eff = it.effectiveStatus(readingMinutesByBook[it.id] ?: 0L)
                eff == "pending" || eff == BookStatus.PLAN_TO_READ
            }
        "completed" ->
            books.filter {
                val eff = it.effectiveStatus(readingMinutesByBook[it.id] ?: 0L)
                eff == BookStatus.COMPLETED
            }
        else -> books
    }

/**
 * Sorts [books] by [sortBy] criteria.
 */
private fun sortBookList(
    books: List<Book>,
    sortBy: String,
): List<Book> =
    when (sortBy) {
        "title" -> books.sortedBy { it.title }
        "author" -> books.sortedBy { it.author ?: "" }
        "last_read" -> books.sortedByDescending { it.updatedAtEpochMillis }
        else -> books // "date_added" — already in insertion order
    }

/**
 * One-shot events from the book-import flow.
 *
 * @property Success The import finished and the book is in the library.
 * @property Failure The import failed; [message] is user-facing.
 */
sealed interface LibraryImportEvent {
    data class Success(
        val title: String,
    ) : LibraryImportEvent

    data class Failure(
        val message: String,
    ) : LibraryImportEvent
}

/**
 * Download state for a cross-device book download.
 */
sealed interface DownloadState {
    data object Idle : DownloadState

    data object Downloading : DownloadState

    data class Error(
        val bookId: String,
        val message: String,
    ) : DownloadState

    data class Success(
        val title: String,
    ) : DownloadState
}

/**
 * LibraryViewModel — Owns the bookshelf (book list) state and exposes a
 * filtered/sorted/searched view of it. Wires Room observation, sync service
 * observation, and delegates filter / import / action logic to dedicated
 * state holders.
 *
 * @param libraryRepository Source of books + reading-time flows.
 * @param importEpubBookUseCase Use case that copies and registers an EPUB file.
 * @param syncService Sync scheduler and state stream.
 * @param appContext Application context (used by the action holder to read file metadata).
 * @param mainDispatcher Dispatcher for state updates; defaults to [Dispatchers.Main].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val importEpubBookUseCase: ImportEpubBookUseCase,
        private val syncService: SyncService,
        @ApplicationContext private val appContext: Context,
        private val catalogSync: SupabaseBookCatalogSync,
        private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        private val readerRepository: ReaderRepository,
        private val getBookProgressUseCase: GetBookProgressUseCase,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(LibraryUiState())

        /**
         * Raw [LibraryUiState] for the Library screen.
         *
         * **Emits when**: any underlying source updates — Room library flow,
         *                total/per-book reading-time flows, sync state, or any
         *                UI action (filter, import, delete, edit,
         *                share, pull-to-refresh).
         * **Initial value**: [LibraryUiState] with `isLoading = true`, empty lists.
         * **Lifecycle**: hot, lifetime-scoped to the ViewModel.
         */
        val uiState: StateFlow<LibraryUiState> = mutableUiState.asStateFlow()

        /**
         * Books after applying status filter, format filter, debounced search, and sort.
         *
         * **Emits when**: `statusFilter` or `filterFormat` changes, the debounced
         *                search query changes (300ms after `searchQuery`), the sort
         *                key changes, or the underlying `books` / `readingMinutesByBook`
         *                change.
         * **Initial value**: `emptyList()` (no books visible until the first upstream
         *                    emit and at least one subscriber is active).
         * **Lifecycle**: `stateIn(WhileSubscribed(5000))` — only collects when at
         *                least one subscriber is active, with a 5s grace period
         *                for config changes.
         *
         * @see onSearchQueryChanged to update the search query (debounced).
         * @see onStatusFilterChanged to filter by status.
         * @see onSortByChanged to change sort order.
         * @see onFilterFormatChanged to filter by format.
         */
        val searchedBooks: StateFlow<List<Book>> =
            mutableUiState
                .map { state ->
                    val byStatus = filterBooks(state.books, state.statusFilter, state.readingMinutesByBook)
                    val byFormat =
                        if (state.filterFormat == "all") {
                            byStatus
                        } else {
                            byStatus.filter { it.format == state.filterFormat }
                        }
                    val bySearch =
                        if (state.debouncedSearchQuery.isBlank()) {
                            byFormat
                        } else {
                            byFormat.filter {
                                it.title.contains(state.debouncedSearchQuery, ignoreCase = true) ||
                                    it.author?.contains(state.debouncedSearchQuery, ignoreCase = true) == true
                            }
                        }
                    sortBookList(bySearch, state.sortBy)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val mutableImportEvents = MutableSharedFlow<LibraryImportEvent>(extraBufferCapacity = 1)

        /**
         * One-shot events for import completion (success / failure).
         *
         * **Emits when**: an EPUB or PDF import finishes.
         * **Backpressure**: SharedFlow with `extraBufferCapacity = 1` so a fast
         *                  second import does not drop the first event silently.
         */
        val importEvents: SharedFlow<LibraryImportEvent> = mutableImportEvents.asSharedFlow()

        private val _uiEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)

        /**
         * One-shot UI events (snackbars, toasts) for non-import actions.
         *
         * **Emits when**: book-action holder emits a UI event (e.g. share, delete confirmation).
         * **Backpressure**: SharedFlow with `extraBufferCapacity = 1`.
         */
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        // ── Holders ────────────────────────────────────────────────────────

        private val bookFilterStateHolder =
            BookFilterStateHolder(
                scope = viewModelScope,
                mainDispatcher = mainDispatcher,
                onStateChanged = { filter ->
                    mutableUiState.update { current ->
                        current.copy(
                            statusFilter = filter.statusFilter,
                            sortBy = filter.sortBy,
                            isGridView = filter.isGridView,
                            searchQuery = filter.searchQuery,
                            debouncedSearchQuery = filter.debouncedSearchQuery,
                            showSearch = filter.showSearch,
                            showFilterSheet = filter.showFilterSheet,
                            filterFormat = filter.filterFormat,
                        )
                    }
                },
            )

        private val bookImportStateHolder =
            BookImportStateHolder(
                importEpubBookUseCase = importEpubBookUseCase,
                libraryRepository = libraryRepository,
                scope = viewModelScope,
                onImportEvent = { mutableImportEvents.tryEmit(it) },
                mainDispatcher = mainDispatcher,
                onStateChanged = { importState ->
                    mutableUiState.update { it.copy(isImporting = importState.isImporting) }
                },
            )

        /**
         * Import state flow — drives the [NexoImportOverlay] in the NavHost.
         *
         * **Emits when**: an import transitions through Extracting → Analyzing →
         * Saving → Idle stages.
         * **Initial value**: [BookImportState.Idle].
         */
        val importState: StateFlow<BookImportState> = bookImportStateHolder.state

        private val bookActionStateHolder =
            BookActionStateHolder(
                libraryRepository = libraryRepository,
                appContext = appContext,
                scope = viewModelScope,
                onUiEvent = { _uiEvent.tryEmit(it) },
                mainDispatcher = mainDispatcher,
                onStateChanged = { action ->
                    mutableUiState.update { current ->
                        current.copy(
                            bookToDelete = action.bookToDelete,
                            bookToShare = action.bookToShare,
                        )
                    }
                },
            )

        private val mutableDownloadableBooks = MutableStateFlow<List<UserBookRow>>(emptyList())

        /** Books from other devices available for download. */
        val downloadableBooks: StateFlow<List<UserBookRow>> = mutableDownloadableBooks.asStateFlow()

        private val mutableDownloadState = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

        /** Per-book download progress state. */
        val downloadState: StateFlow<Map<String, DownloadState>> = mutableDownloadState.asStateFlow()

        private val catalogPollingHolder =
            CatalogPollingHolder(
                catalogSync = catalogSync,
                ioDispatcher = Dispatchers.IO,
                onFastList = { books ->
                    mutableDownloadableBooks.value = books
                    mutableUiState.update { it.copy(downloadableBooks = books, isDownloadableLoading = false) }
                },
                onEnriched = { enriched ->
                    mutableDownloadableBooks.value = enriched
                    mutableUiState.update { it.copy(downloadableBooks = enriched) }
                },
                onLoadingDone = {
                    mutableUiState.update { it.copy(isDownloadableLoading = false) }
                },
            )

        init {
            // Canonical progress map — live Flow via GetBookProgressUseCase.observeProgressPercent (distinctUntilChanged in use case)
            viewModelScope.launch {
                libraryRepository
                    .observeLibrary()
                    .flatMapLatest { books ->
                        if (books.isEmpty()) {
                            flowOf(emptyMap())
                        } else {
                            combine(
                                books.map { book ->
                                    getBookProgressUseCase.observeProgressPercent(book.id).map { pct ->
                                        book.id to pct
                                    }
                                },
                            ) { pairs -> pairs.toMap() }
                        }
                    }.distinctUntilChanged()
                    .collect { map ->
                        mutableUiState.update { it.copy(progressPercentByBook = map) }
                    }
            }
            // ── Lifecycle observations (Room flows) ──
            viewModelScope.launch(mainDispatcher) {
                libraryRepository.observeLibrary().collect { books ->
                    mutableUiState.update { it.copy(books = books, isLoading = false) }
                }
            }

            viewModelScope.launch(mainDispatcher) {
                libraryRepository.observeTotalReadingTime().collect { totalMinutes ->
                    mutableUiState.update { it.copy(totalMinutesRead = totalMinutes) }
                }
            }

            viewModelScope.launch(mainDispatcher) {
                libraryRepository.observeReadingTimeByBook().collect { readingMinutesByBook ->
                    mutableUiState.update { it.copy(readingMinutesByBook = readingMinutesByBook) }
                }
            }

            // ── Sync service observations ──
            viewModelScope.launch(mainDispatcher) {
                syncService.syncState.collect { state ->
                    mutableUiState.update {
                        it.copy(
                            isSyncing = state is DriveSyncState.Running,
                            syncError = (state as? DriveSyncState.Error)?.message,
                            isRefreshing = if (state !is DriveSyncState.Running) false else it.isRefreshing,
                        )
                    }
                }
            }

            // ── Cross-device downloadable books — delegated to holder (structured concurrency) ──
            catalogPollingHolder.start(viewModelScope)
        }

        override fun onCleared() {
            catalogPollingHolder.stop()
            super.onCleared()
        }

        // ── Delegation: Filter / Sort / Search ─────────────────────────────

        /**
         * Updates the active status filter.
         *
         * Side effects:
         * 1. Delegates to [BookFilterStateHolder] which writes the new filter to
         *    `mutableUiState.statusFilter`.
         * 2. `searchedBooks` re-emits with the filtered list on the next tick.
         *
         * @param filter One of `"all"`, `"reading"`, `"pending"`, `"completed"`.
         */
        fun onStatusFilterChanged(filter: String) = bookFilterStateHolder.onStatusFilterChanged(filter)

        /**
         * Updates the active sort key.
         *
         * Side effects:
         * 1. Updates `mutableUiState.sortBy`.
         * 2. `searchedBooks` re-emits sorted by the new key.
         *
         * @param sort One of `"date_added"`, `"title"`, `"author"`, `"last_read"`.
         */
        fun onSortByChanged(sort: String) = bookFilterStateHolder.onSortByChanged(sort)

        /**
         * Toggles between grid and list view.
         *
         * Side effects: flips `mutableUiState.isGridView`.
         */
        fun onToggleView() = bookFilterStateHolder.onToggleView()

        /**
         * Toggles the search input visibility.
         *
         * Side effects: flips `mutableUiState.showSearch`; clearing the query on close
         *               is delegated to the filter state holder.
         */
        fun onToggleSearch() = bookFilterStateHolder.onToggleSearch()

        /**
         * Updates the search query and triggers a debounced re-filter.
         *
         * Side effects:
         * 1. Updates `mutableUiState.searchQuery` immediately (instant UI feedback).
         * 2. Debounces 300ms before updating `mutableUiState.debouncedSearchQuery`.
         * 3. `searchedBooks` re-emits with filtered results.
         *
         * @param query The new search text. Empty string clears the filter (returns all books).
         */
        fun onSearchQueryChanged(query: String) = bookFilterStateHolder.onSearchQueryChanged(query)

        /**
         * Toggles the filter bottom sheet visibility.
         *
         * Side effects: flips `mutableUiState.showFilterSheet`.
         */
        fun onToggleFilterSheet() = bookFilterStateHolder.onToggleFilterSheet()

        /**
         * Updates the active format filter.
         *
         * Side effects:
         * 1. Updates `mutableUiState.filterFormat`.
         * 2. `searchedBooks` re-emits with the format filter applied.
         *
         * @param format `"all"` to disable, or a format token to restrict to that format.
         */
        fun onFilterFormatChanged(format: String) = bookFilterStateHolder.onFilterFormatChanged(format)

        // ── Delegation: Import ─────────────────────────────────────────────

        /**
         * Imports an EPUB file from disk.
         *
         * Side effects:
         * 1. Delegates to [BookImportStateHolder] — sets `isImporting = true`.
         * 2. On completion emits a [LibraryImportEvent.Success] or [LibraryImportEvent.Failure].
         * 3. New book appears in the library via the Room flow observation.
         *
         * @param sourcePath Original filesystem path of the EPUB (used for cover extraction).
         * @param fallbackTitle Title to use if the EPUB metadata has no title.
         * @param inputStreamProvider Suspend function yielding a fresh [InputStream] over the file.
         */
        fun importBookFromEpub(
            sourcePath: String,
            fallbackTitle: String?,
            inputStreamProvider: suspend () -> InputStream?,
        ) = bookImportStateHolder.importBookFromEpub(sourcePath, fallbackTitle, inputStreamProvider)

        /**
         * Imports a PDF book from disk.
         *
         * Side effects: same as [importBookFromEpub] but for PDF files.
         *
         * @param sourcePath Original filesystem path of the PDF.
         * @param fallbackTitle Title to use if the PDF metadata has no title.
         * @param pdfFile The PDF file handle for streaming reads.
         */
        fun importPdfBook(
            sourcePath: String,
            fallbackTitle: String?,
            pdfFile: File,
        ) = bookImportStateHolder.importPdfBook(sourcePath, fallbackTitle, pdfFile)

        /**
         * Force-resets the import state to [BookImportState.Idle].
         *
         * Called by the NavHost overlay watchdog when the import state is stuck
         * non-Idle past a timeout, so the import overlay can never block input
         * indefinitely.
         */
        fun resetImportState() = bookImportStateHolder.resetImportState()

        // ── Delegation: Actions (Delete / Edit / Share / Status) ──────────

        /**
         * Requests deletion of [book] — sets `bookToDelete` so the UI shows a confirmation dialog.
         */
        fun requestDeleteBook(book: Book) = bookActionStateHolder.requestDeleteBook(book)

        /** Dismisses the delete confirmation dialog. */
        fun dismissDeleteDialog() = bookActionStateHolder.dismissDeleteDialog()

        /** Confirms deletion of the book staged in `bookToDelete`. */
        fun confirmDeleteBook() = bookActionStateHolder.confirmDeleteBook()

        /** Local-only delete (keeps Drive file) — mirrors desktop handleHideBook. */
        fun confirmDeleteLocalOnly() = bookActionStateHolder.confirmDeleteLocalOnly()

        /** Local + Drive delete — mirrors desktop handleRemoveBookFromDrive. */
        fun confirmDeleteLocalAndDrive() = bookActionStateHolder.confirmDeleteLocalAndDrive()

        /**
         * Marks [book] as completed in the local library.
         */
        fun onMenuMarkCompleted(book: Book) = bookActionStateHolder.onMenuMarkCompleted(book)

        /**
         * Marks [book] as plan-to-read in the local library.
         */
        fun onMenuMarkPlanToRead(book: Book) = bookActionStateHolder.onMenuMarkPlanToRead(book)

        /**
         * Stages [book] for sharing — sets `bookToShare` so the UI fires a share intent.
         */
        fun onMenuShare(book: Book) = bookActionStateHolder.onMenuShare(book)

        // ── Cross-device download ────────────────────────────────────────

        /**
         * Downloads a book from another device.
         * Updates per-book [downloadState] through Downloading → Success/Error lifecycle.
         *
         * @param bookId The catalog book ID to download.
         */
        fun downloadBook(bookId: String) {
            mutableDownloadState.update { it + (bookId to DownloadState.Downloading) }

            viewModelScope.launch(mainDispatcher) {
                val book = mutableDownloadableBooks.value.find { it.id == bookId }
                val title = book?.title ?: bookId

                catalogSync
                    .downloadRemoteBook(bookId)
                    .onSuccess {
                        mutableDownloadState.update { it + (bookId to DownloadState.Success(title)) }
                        // Remove from downloadable list after a short delay
                        kotlinx.coroutines.delay(DOWNLOAD_REMOVAL_DELAY_MS)
                        mutableDownloadableBooks.value = mutableDownloadableBooks.value.filter { it.id != bookId }
                        mutableUiState.update { current ->
                            current.copy(
                                downloadableBooks = current.downloadableBooks.filter { it.id != bookId },
                                downloadState = current.downloadState + (bookId to DownloadState.Idle),
                            )
                        }
                        mutableDownloadState.update { it - bookId }
                    }.onFailure { error ->
                        DebugLog.error(TAG, "downloadBook: FAILED for $bookId — ${error.message}")
                        // Surface as a transient toast (NavHost collects uiEvent) so the
                        // error never renders as an inline banner that collides with the
                        // cross-device section header.
                        _uiEvent.tryEmit(UiEvent.ShowToast(error.message ?: "Download failed"))
                        val err = DownloadState.Error(bookId, error.message ?: "Download failed")
                        mutableDownloadState.update { it + (bookId to err) }
                    }
            }
        }

        /**
         * Dismisses the download error for a specific book.
         * @param bookId The catalog book ID whose error to dismiss.
         */
        fun dismissDownloadError(bookId: String) {
            mutableDownloadState.update { it - bookId }
        }

        /**
         * Convenience: the first error in [downloadState] for error-banner display,
         * or `null` if no errors.
         */
        val firstDownloadError: StateFlow<DownloadState.Error?>
            get() =
                mutableDownloadState
                    .map { states ->
                        states.values.filterIsInstance<DownloadState.Error>().firstOrNull()
                    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        // ── Sync (stays in ViewModel — cross-cutting concern) ──────────────

        /**
         * Triggers a pull-to-refresh sync.
         *
         * Side effects:
         * 1. Sets `isRefreshing = true` immediately for the swipe indicator.
         * 2. Calls [SyncService.schedulePull] — `isRefreshing` clears when sync state
         *    transitions out of [DriveSyncState.Running] (see init-block sync observation).
         */
        fun onPullToRefresh() {
            mutableUiState.update { it.copy(isRefreshing = true) }
            // Reconciliation now lives in SyncService.bootstrap; nothing VM-local to do.
            viewModelScope.launch(mainDispatcher) {
                val result = syncService.schedulePull()
                if (result.isFailure) {
                    // schedulePull may not emit a terminal syncState when the
                    // service is disabled (StateFlow skips same-reference Disabled).
                    // Clear the refresh indicator so it doesn't hang forever.
                    mutableUiState.update { it.copy(isRefreshing = false) }
                }
            }
        }

        companion object {
            private const val TAG = "LibraryViewModel"
            private const val CATALOG_POLL_INTERVAL_MS = 30_000L
            private const val DOWNLOAD_REMOVAL_DELAY_MS = 2_000L
        }
    }

/**
 * ViewModelProvider.Factory for [LibraryViewModel].
 *
 * Use when the ViewModel cannot be constructor-injected by the DI container
 * (e.g. legacy `viewModels()` call sites).
 */
class LibraryViewModelFactory(
    private val libraryRepository: LibraryRepository,
    private val syncService: SyncService,
    private val appContext: Context,
    private val catalogSync: SupabaseBookCatalogSync,
    private val readerRepository: ReaderRepository,
    private val getBookProgressUseCase: GetBookProgressUseCase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            return LibraryViewModel(
                libraryRepository = libraryRepository,
                importEpubBookUseCase = ImportEpubBookUseCase(libraryRepository),
                syncService = syncService,
                appContext = appContext,
                catalogSync = catalogSync,
                readerRepository = readerRepository,
                getBookProgressUseCase = getBookProgressUseCase,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

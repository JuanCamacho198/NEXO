package com.nexo.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.R
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.session.ReaderPreferences
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.SearchResult
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.presentation.viewmodel.reader.ChromeUiState
import com.nexo.presentation.viewmodel.reader.FullscreenManager
import com.nexo.presentation.viewmodel.reader.ReaderInteractionStateHolder
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleStateHolder
import com.nexo.presentation.viewmodel.reader.ReaderSettingsManager
import com.nexo.presentation.viewmodel.reader.SearchStateHolder
import com.nexo.presentation.viewmodel.reader.SearchUiState
import com.nexo.presentation.viewmodel.reader.SessionUiState
import com.nexo.presentation.viewmodel.reader.SettingsUiState
import com.nexo.presentation.viewmodel.reader.SleepTimerManager
import com.nexo.presentation.viewmodel.reader.SleepTimerUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

typealias BookChapter = com.nexo.presentation.viewmodel.reader.BookChapter

/**
 * ReaderViewModel — Owns the entire state of the Reader screen: book loading,
 * pagination, text selection, highlights, bookmarks, search, sleep timer,
 * settings, and fullscreen. Delegates focused sub-domains to dedicated
 * state holders (`lifecycleHolder`, `interactionHolder`, `searchStateHolder`,
 * `fullscreenManager`, `settingsManager`, `sleepTimerManager`).
 *
 * Slice flows are the single source of truth — see [searchUiState],
 * [chromeUiState], [settingsUiState], [sleepTimerUiState],
 * [sessionUiState], [annotationUiState]. Screens collect slices directly;
 * writes go through the holder owned by the VM (`viewModel.lifecycleHolder`,
 * `viewModel.interactionHolder`, …).
 *
 * Public actions are grouped by responsibility — see the section comments
 * throughout this file. The largest public surface is the text-selection
 * flow on [interactionHolder] (highlight tap, Readium selection, annotate,
 * tag/definition inputs); session navigation lives in the session owner,
 * reached via [lifecycleHolder].
 *
 * @param application Application context (needed for the Android `ViewModel` superclass).
 * @param readerRepository Source of book data, progress, and locator storage.
 * @param readingStatsRepository Source of reading-time statistics.
 * @param updateReadingProgressUseCase Use case that persists reading progress.
 * @param readerPreferences Persistent user settings (font, theme, etc.). May be `null` in tests.
 * @param defaultBookId Book to restore progress for on construction. May be `null`.
 * @param dictionaryRepository Optional dictionary backing for the "add to dictionary" flow.
+ * @param libraryRepository Optional library backing used to resolve a book's
+ *   `filePath` by id when the navigation selection carries a null/blank path
+ *   (e.g. a stale Continuar snapshot). `null` in tests without a library.
 * @param mainDispatcher Dispatcher for state-collection coroutines. Defaults to [Dispatchers.Main].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    application: Application,
    private val readerRepository: ReaderRepository,
    private val readingStatsRepository: ReadingStatsRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgressUseCase,
    private val readerPreferences: ReaderPreferences? = null,
    defaultBookId: String?,
    private val dictionaryRepository: DictionaryRepository? = null,
    private val libraryRepository: LibraryRepository? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val supabaseProgressSync: SupabaseProgressSync? = null,
) : AndroidViewModel(application) {
    /**
     * Sleep timer manager — timer slice owner (SDD reader-facade-split, slice 3).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.sleepTimerManager`), never via a direct import.
     * Only this manager holds the timer MutableStateFlow and mutating funs.
     * The chapter-forwarding glue (`onChapterChanged`) is wired in the
     * lifecycle holder construction above and stays as the single glue line.
     */
    val sleepTimerManager = SleepTimerManager(viewModelScope)

    private val _uiEvent = MutableSharedFlow<UiEvent>()

    /**
     * One-shot UI events for the reader (snackbars, toasts).
     *
     * **Emits when**: lifecycle or interaction holders emit a UI event
     *                (e.g. import errors, save confirmations).
     * **Backpressure**: default SharedFlow buffer; no replay.
     */
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _navigateToLocator = MutableSharedFlow<Locator>()

    /**
     * One-shot navigation events carrying a Readium [Locator] to jump to
     * (used by search-result taps and highlight-tap navigation).
     *
     * **Emits when**: a search result is selected (EPUB path) or a
     *                highlight is tapped that has a stored locator.
     * **Backpressure**: default SharedFlow buffer; no replay.
     */
    val navigateToLocator: SharedFlow<Locator> = _navigateToLocator.asSharedFlow()

    // ── Cluster C state holders (extracted responsibilities) ──────────

    /**
     * Search slice owner (SDD reader-facade-split, slice 1).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.searchStateHolder`), never via a direct holder import.
     * Only this holder holds the search MutableStateFlow and mutating funs.
     */
    val searchStateHolder =
        SearchStateHolder(
            scope = viewModelScope,
            onNavigateToLocator = { loc -> viewModelScope.launch { _navigateToLocator.emit(loc) } },
            onGoToChapter = { lifecycleHolder.goToChapter(it) },
            onGoToPdfPage = { lifecycleHolder.goToPdfPage(it) },
        )

    /**
     * Chrome slice owner (SDD reader-facade-split, slice 2).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.fullscreenManager`), never via a direct holder import.
     * Only this manager holds the chrome MutableStateFlow and mutating funs.
     */
    val fullscreenManager = FullscreenManager()

    /**
     * Settings slice owner (SDD reader-facade-split, slice 2).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.settingsManager`), never via a direct holder import.
     * Only this manager holds the settings MutableStateFlow and mutating funs.
     */
    val settingsManager = ReaderSettingsManager(readerPreferences)

    // ── Cluster A state holder ────────────────────────────────────────

    /**
     * Session slice owner (SDD reader-facade-split, slice 4).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.lifecycleHolder`), never via a direct holder import.
     * Only this holder (and its lifecycle collaborators) holds the session
     * MutableStateFlow and mutating funs.
     */
    val lifecycleHolder =
        ReaderLifecycleStateHolder(
            application = application,
            readerRepository = readerRepository,
            updateReadingProgressUseCase = updateReadingProgressUseCase,
            readingStatsRepository = readingStatsRepository,
            scope = viewModelScope,
            onChapterChanged = { sleepTimerManager.onChapterChanged() },
            onErrorEvent = { _uiEvent.tryEmit(it) },
            onSelectionCleared = { interactionHolder.onSelectionClearedFromLifecycle() },
            onNavigateToLocator = { loc -> viewModelScope.launch { _navigateToLocator.emit(loc) } },
            onBookLoaded = { bookId -> interactionHolder.observeBook(bookId) },
            mainDispatcher = mainDispatcher,
            supabaseProgressSync = supabaseProgressSync,
        )

    // ── Cluster B state holder ────────────────────────────────────────

    /**
     * Annotation slice owner (SDD reader-uiState-cleanup, S7).
     *
     * Screens stay VM-scoped: they reach the owner through the VM
     * (`viewModel.interactionHolder`), never via a direct holder import.
     * Public since S7 deleted the 30 `@Deprecated` VM delegates — callers
     * invoke holder methods directly for writes and collect
     * [annotationUiState] for reads.
     */
    val interactionHolder =
        ReaderInteractionStateHolder(
            readerRepository = readerRepository,
            dictionaryRepository = dictionaryRepository,
            scope = viewModelScope,
            onEvent = { _uiEvent.tryEmit(it) },
            mainDispatcher = mainDispatcher,
        )

    /**
     * Emitted when the WebView/Readium selection should be cleared.
     *
     * **Emits when**: the lifecycle holder detects a book swap that should
     *                wipe the in-flight selection (delegated to
     *                [ReaderInteractionStateHolder]).
     * **Backpressure**: default SharedFlow buffer; no replay.
     */
    val clearSelectionEvent: SharedFlow<Unit> = interactionHolder.clearSelectionEvent

    /**
     * Search slice re-export (SDD reader-facade-split, slice 1).
     *
     * Read-only view of the search owner state — the single source of truth
     * for active flag, query, results, and in-flight flag.
     */
    val searchUiState: StateFlow<SearchUiState> = searchStateHolder.state

    /**
     * Chrome slice re-export (SDD reader-facade-split, slice 2).
     *
     * Read-only view of the chrome owner state — the single source of truth
     * for fullscreen.
     */
    val chromeUiState: StateFlow<ChromeUiState> = fullscreenManager.state

    /**
     * Settings slice re-export (SDD reader-facade-split, slice 2).
     *
     * Read-only view of the settings owner state — the single source of truth
     * for reader settings and the split-settings sheet flag.
     */
    val settingsUiState: StateFlow<SettingsUiState> = settingsManager.state

    /**
     * Sleep-timer slice re-export (SDD reader-facade-split, slice 3).
     *
     * Read-only view of the timer owner state — the single source of truth
     * for active/remaining/finished/EOC state.
     */
    val sleepTimerUiState: StateFlow<SleepTimerUiState> = sleepTimerManager.state

    /**
     * Session slice re-export (SDD reader-facade-split, slice 4).
     *
     * Read-only view of the session owner state — the single source of truth
     * for book identity, loading state, chapter/position, and progress.
     */
    val sessionUiState: StateFlow<SessionUiState> = lifecycleHolder.state

    /**
     * Annotation slice re-export (SDD reader-facade-split, slice 5).
     *
     * Read-only view of the annotation owner state — the single source of
     * truth for text selection, highlights, bookmarks, note modal, tag
     * input, highlights panel, and the definition input.
     *
     * Owner: [interactionHolder] ([ReaderInteractionStateHolder]). Reads via
     * [annotationUiState]; writes via `viewModel.interactionHolder.X` (the
     * holder is reachable through the VM, never imported directly from screens).
     */
    val annotationUiState: StateFlow<AnnotationUiState> = interactionHolder.state

    init {
        if (!defaultBookId.isNullOrBlank()) {
            lifecycleHolder.restoreProgressForBook(defaultBookId)
        }

        // S7: no VM-side merge collectors remain. Session state is owned by
        // the lifecycle holder (previewText included since S4); annotation
        // state is owned by the interaction holder, whose Room highlights
        // observation (`observeBook`) feeds annotationUiState directly.
        // Typography -> exact reflow wiring lives in the lifecycle
        // owner (SDD reader-facade-split, T5); the VM only supplies density.
        lifecycleHolder.observeTypographyConfig(
            settingsManager.state,
            getApplication<android.app.Application>().resources.displayMetrics.density,
        )
    }

    // ── Book Loading ──────────────────────────────────────────────────

    // Slice 4 (SDD reader-facade-split, T5): the navigateToCfiAfterLoad
    // pass-through delegate was deleted — callers reach the owner through
    // the VM (`viewModel.lifecycleHolder.navigateToCfiAfterLoad(...)`).
    // [loadBook] below stays: it orchestrates holders + fullscreen + the
    // pending-CFI wait, it is not a pass-through.

    /**
     * Loads a new book into the reader, replacing any current selection.
     *
     * Side effects:
     * 1. Resets the annotation coordinator (clears in-flight selection).
     * 2. Enters immersive (fullscreen) reading mode so the reader opens
     *    with the chrome auto-hidden.
     * 3. Delegates to `lifecycleHolder.loadBook` — the lifecycle state holder
     *    will emit a new [SessionUiState] with the book metadata, chapters,
     *    publication (EPUB), and `isLoading = true` until the book is ready.
     *    Highlights for the new book reach [annotationUiState] through the
     *    interaction holder's `observeBook` (wired via `onBookLoaded`).
     *
     * @param bookId Database id of the book to load.
     * @param filePath Absolute filesystem path to the book file.
     * @param format `"epub"` or `"pdf"`. Defaults to `"epub"`.
     */
    fun loadBook(
        bookId: String,
        filePath: String,
        format: String = "epub",
    ) {
        interactionHolder.resetCoordinator()
        fullscreenManager.enterFullscreen()
        lifecycleHolder.loadBook(bookId, filePath, format)

        // When loading completes, apply any pending CFI navigation
        // (session-owned; set by NavHost via navigateToCfiAfterLoad).
        viewModelScope.launch(mainDispatcher) {
            lifecycleHolder.state.first { !it.isLoading }
            lifecycleHolder.applyPendingCfi()
        }
    }

    /**
     * Loads a book tolerating a null/blank navigation path.
     *
     * The Continuar selection snapshot can lag the nav write, so the Reader
     * may arrive with a known [bookId] but a null/blank [filePath] while the
     * file itself exists on disk. In that case the canonical `file_path` is
     * re-resolved from the library by id instead of erroring immediately.
     * Only when the id is blank or the DB has no usable path does the reader
     * surface `book_not_found` with its retry path.
     *
     * @param bookId Database id of the book to load.
     * @param filePath Navigation-carried absolute path; may be null/blank.
     * @param format `"epub"` or `"pdf"`. Defaults to `"epub"`.
     */
    fun loadBookWithFallback(
        bookId: String,
        filePath: String?,
        format: String = "epub",
    ) {
        val navPath = filePath?.takeIf { it.isNotBlank() }
        if (navPath != null) {
            loadBook(bookId, navPath, format)
            return
        }
        if (bookId.isBlank()) {
            DebugLog.warn(TAG, "loadBookWithFallback skipped: blank book id")
            reportBookNotFound()
            return
        }
        viewModelScope.launch(mainDispatcher) {
            val resolved =
                try {
                    libraryRepository?.getBookById(bookId)?.filePath?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            if (resolved != null) {
                DebugLog.info(TAG, "loadBookWithFallback resolved path from library bookId=$bookId")
                loadBook(bookId, resolved, format)
            } else {
                DebugLog.warn(TAG, "loadBookWithFallback unresolved bookId=$bookId")
                reportBookNotFound()
            }
        }
    }

    private fun reportBookNotFound() {
        lifecycleHolder.reportLoadError(getApplication<Application>().getString(R.string.book_not_found))
    }

    // ── Readium Bridge ──────────────────────────────────────────────
    // (SDD reader-uiState-cleanup, S4+S7): the VM-side preview collector,
    // its private extractChapterPreviewText helper, and the S7 aggregate
    // (uiState + combines + merge collectors + 30 annotation delegates) were
    // deleted — the session owner (ReaderLifecycleStateHolder) is the single
    // source of previewText and the interaction holder owns annotation state.

    // ── Readium Bridge ──────────────────────────────────────────────
    // Slice 4 (SDD reader-facade-split, T5): onReadiumLocatorChanged,
    // onReadiumViewportChanged and onPdfDocumentLoaded pass-through delegates
    // were deleted — callers reach the owner through the VM
    // (`viewModel.lifecycleHolder`).

    // ── Search (Gap 3) ──────────────────────────────────────────────
    // Slice 1 (SDD reader-facade-split, T2): search state lives in
    // [searchStateHolder] and is re-exported as [searchUiState]. The
    // toggle/query pass-through delegates were deleted — callers reach the
    // owner through the VM (`viewModel.searchStateHolder`). The funs below
    // are owner-backed actions still routed via the VM, not pass-throughs.

    /** Clears the current search query and results without closing the search panel. */
    fun onClearSearch() = searchStateHolder.onClearSearch()

    /** Dismisses the search panel and clears any in-flight search state. */
    fun onDismissSearch() = searchStateHolder.onDismissSearch()

    /**
     * Receives search results from the native PDF layer (JSON-encoded) and
     * surfaces them as [SearchResult]s in [SearchUiState.searchResults].
     *
     * @param json JSON payload produced by the PDF reader's search API.
     */
    fun onPdfSearchResults(json: String) = searchStateHolder.onPdfSearchResults(json)

    /**
     * Jumps the reader to the location of [result].
     *
     * Side effects:
     * 1. For EPUB: emits a [Locator] on [navigateToLocator] to scroll to the match.
     * 2. For PDF: navigates via the session owner.
     * 3. Updates `currentChapterIndex` in the session slice for the EPUB path.
     *
     * Session fields come from the session owner (slice 4), not the merge.
     */
    fun onSearchResultSelected(result: SearchResult) {
        val session = lifecycleHolder.state.value
        searchStateHolder.onSearchResultSelected(
            result = result,
            publication = session.readiumPublication,
            bookFormat = session.bookFormat,
            currentChapterIndex = session.currentChapterIndex,
        )
    }

    // ── Annotation writes (Gap 4+) — owned by Cluster B ────────────
    // S7: the 30 @Deprecated annotation delegates were deleted — callers
    // reach the owner through the VM (`viewModel.interactionHolder.*`).
    // [onHighlightSelected] below stays: it orchestrates holders + sheet
    // state, it is not a pass-through.

    // Slice 4 (SDD reader-facade-split, T5): the onToggleTocSheet
    // pass-through delegate was deleted — callers reach the owner through
    // the VM (`viewModel.lifecycleHolder.onToggleTocSheet()`).

    /**
     * Navigates to the position of [highlight].
     *
     * Side effects:
     * 1. For PDF highlights (`cfiRange` starts with `pdfpage:`): jumps to the
     *    stored page via the session owner.
     * 2. For EPUB highlights with a stored Readium locator: emits a
     *    [Locator] on [navigateToLocator] for precise position.
     * 3. For legacy EPUB highlights without a locator: extracts the chapter
     *    index from the CFI string and navigates to the chapter start.
     * 4. Closes the highlights sheet.
     */
    fun onHighlightSelected(highlight: Highlight) {
        val cfi = highlight.cfiRange
        if (cfi.startsWith("pdfpage:")) {
            val page = cfi.removePrefix("pdfpage:").toIntOrNull()
            if (page != null) lifecycleHolder.goToPdfPage(page)
        } else {
            // EPUB path: prefer the persisted Readium Locator (precise CFI/position)
            // over the legacy chapter-only CFI fallback.
            val locatorJson = highlight.locatorJson
            val locator = locatorJson?.let { CfiMigrator.jsonToLocator(it) }
            if (locator != null) {
                viewModelScope.launch(mainDispatcher) {
                    _navigateToLocator.emit(locator)
                }
            } else {
                // Legacy CFI without a stored locator: extract spine index and
                // map to TOC list position (spine offset fix).
                val chapterMatch = Regex("/6/(\\d+)").find(cfi)
                val spineIndex =
                    chapterMatch
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.minus(1)
                if (spineIndex != null) {
                    val chapters = lifecycleHolder.state.value.chapters
                    val listPos = chapters.indexOfFirst { it.index == spineIndex }.takeIf { it >= 0 }
                    if (listPos !=
                        null
                    ) {
                        lifecycleHolder.goToChapter(listPos)
                    } else {
                        lifecycleHolder.goToChapter(spineIndex.coerceIn(chapters.indices))
                    }
                }
            }
        }
        // S7: the aggregate is gone — close the sheet through the annotation
        // owner. Toggle is the owner's only sheet mutator; gate on the live
        // slice value so an already-closed sheet is never opened.
        if (interactionHolder.state.value.showHighlightsSheet) interactionHolder.onToggleHighlightsPanel()
    }

    // ── Bookmarks ─────────────────────────────────────────────────

    /**
     * Creates a bookmark at [cfiLocation] for [bookId] with the given [titleOrSnippet].
     *
     * @param bookId Database id of the book.
     * @param cfiLocation CFI string (EPUB) or `"pdfpage:<n>"` marker.
     * @param titleOrSnippet User-supplied title or snippet to label the bookmark.
     */
    fun createBookmark(
        bookId: String,
        cfiLocation: String,
        titleOrSnippet: String,
    ) = interactionHolder.createBookmark(bookId, cfiLocation, titleOrSnippet)

    /**
     * Creates a bookmark at the reader's current position using the
     * active Readium locator (EPUB) or PDF page as the anchor.
     */
    fun createBookmarkFromCurrentPosition() {
        val session = lifecycleHolder.state.value
        interactionHolder.createBookmarkFromCurrentPosition(
            selectedBookId = session.selectedBookId,
            bookFormat = session.bookFormat,
            currentPdfPage = session.currentPdfPage,
            chapters = session.chapters,
            currentChapterIndex = session.currentChapterIndex,
            readiumLocator = session.readiumLocator,
        )
    }

    companion object {
        private const val TAG = "ReaderViewModel"
    }

    // ── aA Settings ──────────────────────────────────────────────────
    // Slice 2 (SDD reader-facade-split, T3): settings state lives in
    // [settingsManager] and is re-exported as [settingsUiState]. The
    // split-settings/palette pass-through delegates were deleted — callers
    // reach the owner through the VM (`viewModel.settingsManager`).

    // ── Fullscreen ───────────────────────────────────────────────────
    // Slice 2 (SDD reader-facade-split, T3): chrome state lives in
    // [fullscreenManager] and is re-exported as [chromeUiState]. The
    // toggle pass-through delegate was deleted — callers reach the owner
    // through the VM (`viewModel.fullscreenManager`).

    // ── Sleep Timer ────────────────────────────────────────────────
    // Slice 3 (SDD reader-facade-split, T4): timer state lives in
    // [sleepTimerManager] and is re-exported as [sleepTimerUiState]. The
    // start/cancel/dismiss/format pass-through delegates were deleted —
    // callers reach the owner through the VM
    // (`viewModel.sleepTimerManager`). The single documented glue line
    // (session chapter -> `timer.onChapterChanged()`) is kept verbatim in
    // the lifecycle holder wiring above.

    // ── Reader Settings ──────────────────────────────────────────────
    // Slice 2 (SDD reader-facade-split, T3): replaced by [settingsManager].
    // Callers reach the owner through the VM
    // (`viewModel.settingsManager.updateReaderSettings(...)`).

    // ── Cluster A — Delegated to ReaderLifecycleStateHolder ─────────
    // Slice 4 (SDD reader-facade-split, T5): all session pass-through
    // delegates were deleted — callers reach the owner through the VM
    // (`viewModel.lifecycleHolder.goToChapter(...)` and friends).

    override fun onCleared() {
        lifecycleHolder.onCleared()
        interactionHolder.onCleared()
        super.onCleared()
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val readerRepository: ReaderRepository,
    private val readingStatsRepository: ReadingStatsRepository,
    private val readerPreferences: ReaderPreferences,
    private val defaultBookId: String?,
    private val dictionaryRepository: DictionaryRepository? = null,
    private val libraryRepository: LibraryRepository? = null,
    private val supabaseProgressSync: SupabaseProgressSync? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            return ReaderViewModel(
                application = application,
                readerRepository = readerRepository,
                readingStatsRepository = readingStatsRepository,
                updateReadingProgressUseCase = UpdateReadingProgressUseCase(readerRepository),
                readerPreferences = readerPreferences,
                defaultBookId = defaultBookId,
                dictionaryRepository = dictionaryRepository,
                libraryRepository = libraryRepository,
                supabaseProgressSync = supabaseProgressSync,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

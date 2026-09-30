package com.nexo.presentation.viewmodel.reader

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.lifecycle.EpubBookLoader
import com.nexo.presentation.viewmodel.reader.lifecycle.PdfBookLoader
import com.nexo.presentation.viewmodel.reader.lifecycle.ReadingNavigator
import com.nexo.presentation.viewmodel.reader.lifecycle.ReadingProgressTracker
import com.nexo.presentation.viewmodel.reader.lifecycle.ReadingSessionRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Facade that delegates to 5 collaborators behind retained [ReaderLifecycleStateHolder] API.
 *
 * Preserves public surface verbatim; no behavior change. Marked @Deprecated for removal in PR #2.
 */
@Deprecated("Delegate to lifecycle collaborators — to be removed in PR #2")
class ReaderLifecycleStateHolder(
    private val application: Application,
    private val readerRepository: ReaderRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgressUseCase,
    private val readingStatsRepository: ReadingStatsRepository,
    private val scope: CoroutineScope,
    private val onChapterChanged: () -> Unit,
    private val onErrorEvent: (UiEvent) -> Unit,
    private val onSelectionCleared: () -> Unit = {},
    private val onNavigateToLocator: (Locator) -> Unit = {},
    private val onBookLoaded: (bookId: String) -> Unit = {},
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val supabaseProgressSync: SupabaseProgressSync? = null,
) {
    private val _state = MutableStateFlow(ReaderLifecycleState())
    val state: StateFlow<ReaderLifecycleState> = _state.asStateFlow()

    companion object {
        private const val TAG = "ReaderLifecycleStateHolder"
        private const val MAX_PROGRESS_PERCENT = 99f
        private const val READING_TIME_TICK_MS = 60_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val CHAPTER_TEXT_LIMIT = 600
    }

    data class PaginationInfo(
        val totalPositions: Int,
        val currentPosition: Int,
        val estimatedPagesViaChars: Int,
        val viewportH: Int,
        val viewportW: Int,
    )

    // Collaborators — single StateFlow ownership via _state accessor
    private val progressTracker =
        ReadingProgressTracker(
            application = application,
            state = _state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            readerRepository = readerRepository,
            updateReadingProgressUseCase = updateReadingProgressUseCase,
            onSelectionCleared = onSelectionCleared,
            onErrorEvent = onErrorEvent,
        )

    private val epubLoader =
        EpubBookLoader(
            application = application,
            readerRepository = readerRepository,
            state = _state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            supabaseProgressSync = supabaseProgressSync,
            onErrorEvent = onErrorEvent,
            onNavigateToLocator = onNavigateToLocator,
            onBookLoaded = onBookLoaded,
            onProgressDisplay = { progressTracker.updateProgressDisplay() },
        )

    private val pdfLoader =
        PdfBookLoader(
            application = application,
            state = _state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            onErrorEvent = onErrorEvent,
            onBookLoaded = onBookLoaded,
            onProgressDisplay = { progressTracker.updateProgressDisplay() },
        )

    private val navigator =
        ReadingNavigator(
            state = _state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            readerRepository = readerRepository,
            updateReadingProgressUseCase = updateReadingProgressUseCase,
            onChapterChanged = onChapterChanged,
            onNavigateToLocator = onNavigateToLocator,
            onSelectionCleared = onSelectionCleared,
            onProgressDisplay = { progressTracker.updateProgressDisplay() },
        )

    private val sessionRecorder =
        ReadingSessionRecorder(
            state = _state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            readingStatsRepository = readingStatsRepository,
        )

    // loadEpoch preserved via epubLoader epoch (single source; pdf path increments separately but facade exposes unified)
    private val loadEpoch: Long
        get() = epubLoader.loadEpoch

    /**
     * Derives the split-settings preview text from the current chapter's
     * real content (EPUB only) into session-owned [ReaderLifecycleState.previewText].
     *
     * Session-side owner of the `previewText` derivation (SDD
     * reader-uiState-cleanup, gate 1.3): same trigger
     * (`bookFormat` + `readiumPublication` + `currentChapterIndex`,
     * distinct) and same extraction as the ViewModel collector it shadows.
     * PDFs have no extractable HTML text, so `previewText` stays blank and
     * callers fall back to the selected text / chapter title.
     *
     * Scope ownership: the feed runs in a holder-owned [previewScope],
     * cancelled in [onCleared], instead of the injected [scope] — it is an
     * app-lifetime reactive feed with no paired stop action (unlike the
     * ticker/observer jobs started on explicit calls), so it must not
     * become an uncompletable child of a caller-owned test scope.
     * PR #2 (facade removal) must carry this feed into the surviving
     * lifecycle collaborator.
     *
     * Single source (SDD reader-uiState-cleanup, S4): the ViewModel
     * collector feeding `mutableUiState.previewText` was deleted when
     * `ReaderScreenOverlaysHost` migrated to `sessionUiState.previewText` —
     * this feed is now the only preview derivation.
     */
    private val previewScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    init {
        startPreviewDerivation()
    }

    private fun startPreviewDerivation() {
        previewScope.launch(mainDispatcher) {
            _state
                .map { Triple(it.bookFormat, it.readiumPublication, it.currentChapterIndex) }
                .distinctUntilChanged()
                .collect { (bookFormat, publication, chapterIndex) ->
                    val excerpt = extractChapterPreviewText(publication, bookFormat, chapterIndex)
                    _state.update { current ->
                        current.copy(previewText = excerpt ?: "")
                    }
                }
        }
    }

    /**
     * Extracts a plain-text excerpt (~600 chars) of the chapter at
     * [chapterIndex] from the current Readium [Publication]. Returns `null`
     * for PDFs and any resource that cannot be read.
     */
    private suspend fun extractChapterPreviewText(
        publication: Publication?,
        bookFormat: String?,
        chapterIndex: Int,
    ): String? {
        if (publication == null || bookFormat != "epub") return null
        return withContext(Dispatchers.IO) {
            try {
                // chapterIndex is TOC list position, NOT spine index — resolve via chapters mapping
                val chapters = _state.value.chapters
                val link =
                    if (chapterIndex in chapters.indices) {
                        val ch = chapters[chapterIndex]
                        val normFile =
                            ch.href
                                .substringBefore('#')
                                .substringBefore('?')
                                .substringAfterLast('/')
                                .lowercase()
                        publication.readingOrder.firstOrNull {
                            it.href
                                .toString()
                                .substringAfterLast('/')
                                .substringBefore('#')
                                .substringBefore('?')
                                .lowercase() == normFile
                        } ?: publication.readingOrder.getOrNull(ch.index)
                            ?: publication.readingOrder.getOrNull(chapterIndex)
                    } else {
                        publication.readingOrder.getOrNull(chapterIndex)
                    } ?: return@withContext null
                val resource = publication.get(link) ?: return@withContext null
                val readResult = resource.read()
                val bytes = readResult.getOrNull() ?: return@withContext null
                bytes
                    .decodeToString()
                    .replace(Regex("<[^>]*>"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(CHAPTER_TEXT_LIMIT)
            } catch (_: Exception) {
                null
            }
        }
    }

    @Deprecated("Delegate to ReadingSessionRecorder — to be removed in PR #2")
    fun setActiveUserId(userId: String) {
        sessionRecorder.setActiveUserId(userId)
    }

    private fun getActiveUserId(): String = sessionRecorder.activeUserId

    // ── Book Loading ────────────────────────────────────────────────

    @Deprecated("Delegate to BookLoader — to be removed in PR #2")
    fun loadBook(
        bookId: String,
        filePath: String,
        format: String = "epub",
    ) {
        val startTime = System.currentTimeMillis()
        _state.update {
            it.copy(
                selectedBookId = bookId,
                bookFilePath = filePath,
                bookFormat = format,
                isLoading = true,
                error = null,
                chapters = emptyList(),
                currentChapterIndex = 0,
                currentPdfPage = 0,
                totalPdfPages = 0,
                progressPercent = 0f,
                progressLabel = "",
                readiumPublication = null,
                readiumLocator = null,
            )
        }
        when (format.lowercase()) {
            "pdf" -> pdfLoader.loadPdfBook(bookId, filePath, startTime)
            else -> epubLoader.loadEpubBook(bookId, filePath)
        }
    }

    @Deprecated("Delegate to EpubBookLoader — to be removed in PR #2")
    fun loadEpubBook(
        bookId: String,
        filePath: String,
    ) {
        epubLoader.loadEpubBook(bookId, filePath)
    }

    @Deprecated("Delegate to PdfBookLoader — to be removed in PR #2")
    fun onPdfDocumentLoaded(pages: Int) {
        pdfLoader.onPdfDocumentLoaded(pages)
    }

    /**
     * Surfaces an explicit load error (e.g. guard-skipped loadBook with a
     * blank id / null path) so the reader shows retry UI instead of holding
     * the initial `isLoading = true` spinner forever.
     */
    fun reportLoadError(message: String) {
        _state.update { it.copy(isLoading = false, error = message) }
    }

    // ── Progress / Locator ───────────────────────────────────────

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun onReadiumLocatorChanged(locator: Locator) {
        progressTracker.onReadiumLocatorChanged(locator)
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun onReadiumViewportChanged(
        height: Int,
        width: Int = 0,
    ) {
        progressTracker.onReadiumViewportChanged(height, width)
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun onTypographyChanged() {
        progressTracker.onTypographyChanged()
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun onTypographyConfigChanged(
        fontSizeSp: Float,
        lineHeight: Float,
        pageMarginsDp: Float = 16f,
        density: Float = 3f,
    ) {
        progressTracker.onTypographyConfigChanged(fontSizeSp, lineHeight, pageMarginsDp, density)
    }

    internal fun updateProgressDisplay() {
        progressTracker.updateProgressDisplay()
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun updateProgress(
        bookId: String,
        cfiLocation: String,
        percentage: Float,
    ) {
        progressTracker.updateProgress(bookId, cfiLocation, percentage)
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun restoreProgressForBook(bookId: String) {
        progressTracker.restoreProgressForBook(bookId)
    }

    @Deprecated("Delegate to ReadingProgressTracker — to be removed in PR #2")
    fun onProgressChange(percent: Float) {
        // Preserve original behavior: update display + navigate
        val clamped = percent.coerceIn(0f, 100f)
        _state.update { it.copy(progressPercent = clamped) }
        if (_state.value.selectedBookId == null) return
        val s = _state.value
        if (s.totalPdfPages > 0) {
            val pageIndex = ((clamped / 100f) * s.totalPdfPages).toInt().coerceIn(0, s.totalPdfPages - 1)
            if (pageIndex != s.currentPdfPage) {
                _state.update { it.copy(currentPdfPage = pageIndex) }
                // delegate pdf progress persist via tracker
                progressTracker.updateProgress(
                    s.selectedBookId ?: return,
                    "pdfpage:$pageIndex",
                    (
                        (pageIndex + 1).toFloat() /
                            s.totalPdfPages *
                            100f
                    ).coerceIn(0f, 100f),
                )
                progressTracker.updateProgressDisplay()
                navigator.goToPdfPage(pageIndex)
            }
        } else if (s.chapters.isNotEmpty()) {
            val chapterIndex = ((clamped / 100f) * s.chapters.size).toInt().coerceIn(0, s.chapters.size - 1)
            if (chapterIndex != s.currentChapterIndex) {
                _state.update { it.copy(currentChapterIndex = chapterIndex) }
                // persist via tracker path
                val percentage = (((chapterIndex + 1).toFloat() / s.chapters.size) * 100f).coerceIn(0f, MAX_PROGRESS_PERCENT)
                val chapter = s.chapters.getOrNull(chapterIndex)
                val spineIndex = chapter?.let { it.index + 1 } ?: (chapterIndex + 1)
                progressTracker.updateProgress(s.selectedBookId ?: return, "epubcfi(/6/$spineIndex)", percentage)
                onChapterChanged()
                navigator.goToChapter(chapterIndex)
            }
        }
    }

    // ── Navigation ───────────────────────────────────────────────

    fun onHighlightSelected(highlight: com.nexo.domain.model.Highlight) {
        val cfi = highlight.cfiRange
        if (cfi.startsWith("pdfpage:")) {
            val page = cfi.removePrefix("pdfpage:").toIntOrNull()
            if (page != null) navigator.goToPdfPage(page)
        } else {
            val locatorJson = highlight.locatorJson
            val locator = locatorJson?.let { CfiMigrator.jsonToLocator(it) }
            if (locator != null) {
                scope.launch(mainDispatcher) { onNavigateToLocator(locator) }
            } else {
                val chapterMatch = Regex("/6/(\\d+)").find(cfi)
                val spineIndex =
                    chapterMatch
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.minus(1)
                if (spineIndex != null) {
                    val chapters = _state.value.chapters
                    val listPos = navigator.spineIndexToListPosition(spineIndex, chapters)
                    if (listPos != null) navigator.goToChapter(listPos) else navigator.goToChapter(spineIndex.coerceIn(chapters.indices))
                }
            }
        }
        _state.update { it.copy(showTocSheet = false) }
    }

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToNextChapter() = navigator.goToNextChapter()

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToPreviousChapter() = navigator.goToPreviousChapter()

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToChapter(listPosition: Int) = navigator.goToChapter(listPosition)

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToNextPdfPage() = navigator.goToNextPdfPage()

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToPreviousPdfPage() = navigator.goToPreviousPdfPage()

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToPage(pageNumber: Int) = navigator.goToPage(pageNumber)

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun goToPdfPage(pageIndex: Int) = navigator.goToPdfPage(pageIndex)

    @Deprecated("Delegate to ReadingNavigator — to be removed in PR #2")
    fun onTapZone(isLeftZone: Boolean) = navigator.onTapZone(isLeftZone)

    @Deprecated("Delegate to TocBuilder — to be removed in PR #2")
    fun onToggleTocSheet() {
        _state.update { it.copy(showTocSheet = !it.showTocSheet) }
    }

    // ── Session ─────────────────────────────────────────────────

    @Deprecated("Delegate to ReadingSessionRecorder — to be removed in PR #2")
    fun onReaderOpened() = sessionRecorder.onReaderOpened()

    @Deprecated("Delegate to ReadingSessionRecorder — to be removed in PR #2")
    fun onReaderPaused() = sessionRecorder.onReaderPaused()

    @Deprecated("Delegate to ReadingSessionRecorder — to be removed in PR #2")
    fun onReaderBackgrounded() = sessionRecorder.onReaderBackgrounded()

    fun onCleared() {
        previewScope.cancel()
        sessionRecorder.onCleared()
        progressTracker.onCleared()
        epubLoader.onCleared()
        pdfLoader.onCleared()
        navigator.onCleared()
    }

    // ── Pending CFI after load (SDD reader-facade-split, T5) ─────────
    // Owned by ReadingNavigator; the facade preserves the surface verbatim.

    internal val pendingCfiAfterLoad: String?
        get() = navigator.pendingCfiAfterLoad

    fun navigateToCfiAfterLoad(cfiRange: String) = navigator.navigateToCfiAfterLoad(cfiRange)

    @VisibleForTesting
    internal fun applyPendingCfi() = navigator.applyPendingCfi()

    // ── Typography → reflow wiring (SDD reader-facade-split, T5) ─────
    // Moved from ReaderViewModel init so the reflow subscription lives in
    // the lifecycle owner. The VM only supplies the settings flow + density.

    /**
     * Observes reader-settings changes and recomputes footer remaining pages
     * within one frame (exact reflow). Margins are fixed at 16dp (VM parity).
     */
    fun observeTypographyConfig(
        settings: StateFlow<ReaderSettingsState>,
        density: Float,
    ) {
        scope.launch(mainDispatcher) {
            settings.collect { s ->
                val rs = s.readerSettings
                progressTracker.onTypographyConfigChanged(
                    rs.fontSize.sizePx.toFloat(),
                    rs.lineHeight.value,
                    16f,
                    density,
                )
            }
        }
    }

    // ── Test helpers ────────────────────────────────────────────

    @VisibleForTesting
    internal fun setChaptersForTest(chapters: List<BookChapter>) {
        _state.update { it.copy(chapters = chapters) }
    }

    @VisibleForTesting
    internal fun setPdfStateForTest(
        selectedBookId: String = "",
        totalPages: Int = 0,
        currentPage: Int = 0,
    ) {
        _state.update {
            it.copy(selectedBookId = selectedBookId, totalPdfPages = totalPages, currentPdfPage = currentPage, bookFormat = "pdf")
        }
        progressTracker.updateProgressDisplay()
    }

    @VisibleForTesting
    internal fun setBookLoadedForTest(publication: Publication? = null) {
        _state.update { it.copy(isLoading = false, readiumPublication = publication, bookFormat = "epub") }
        progressTracker.updateProgressDisplay()
    }

    @VisibleForTesting
    internal fun setEpubStateForTest(
        chapters: List<BookChapter>,
        currentChapterIndex: Int = 0,
        selectedBookId: String = "",
    ) {
        _state.update {
            it.copy(
                chapters = chapters,
                currentChapterIndex = currentChapterIndex,
                bookFormat = "epub",
                totalPdfPages = 0,
                selectedBookId = selectedBookId,
            )
        }
        progressTracker.updateProgressDisplay()
    }
}

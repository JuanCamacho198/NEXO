package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import com.nexo.domain.event.UiEvent
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.ChapterTextLengthCache
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import com.nexo.presentation.viewmodel.reader.ReadingProgressCalculator
import com.nexo.presentation.viewmodel.reader.chapterPagesRemaining
import com.nexo.presentation.viewmodel.reader.cleanChapterTitle
import com.nexo.presentation.viewmodel.reader.visibleTextLength
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Url

/**
 * Wraps ReadingProgressCalculator and handles locator/viewport/typography progress.
 *
 * Preserves verbatim: reflection chain (getPositions -> field -> members) lives
 * inside ReadingProgressCalculator; display vs persisted split; restoreProgressForBook.
 *
 * Persistence is ARMED, not unconditional. See [ReaderLifecycleState.progressPersistenceArmed]:
 * the navigator's first emission precedes the saved position being applied, so
 * persisting it would replace the reader's real position with the cover at 0%.
 */
internal class ReadingProgressTracker(
    private val application: Application,
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val readerRepository: ReaderRepository,
    private val activeUser: ActiveUserProvider,
    private val updateReadingProgressUseCase: UpdateReadingProgressUseCase,
    private val onSelectionCleared: () -> Unit = {},
    private val onErrorEvent: (UiEvent) -> Unit = {},
) : Clearable {
    private var observeProgressJob: Job? = null
    private var typographySnapshot: ReadingProgressCalculator.ViewportTypography? = null
    private var densitySnapshot: Float = 3f

    /** Readium positions of the open publication; see [ensurePositions]. */
    private var positions: List<Locator>? = null
    private var positionsFor: Publication? = null
    private var positionsJob: Job? = null

    /** First totalProgression of each reading-order resource, by href. */
    private var resourceStartFractions: List<Pair<String, Float?>>? = null

    /**
     * The saved position this session opened at, handed over by the loader from
     * the SAME synchronous read that produced the reader's initial locator.
     * `null` until [openOnSavedPosition] runs, and for a book with no saved row.
     * See [mayPersist].
     */
    private var protectedPosition: ReadingProgress? = null

    companion object {
        private const val MAX_PROGRESS_PERCENT = 99f

        /**
         * The writer's own marker (`onReadiumLocatorChanged` writes
         * `"readium:<href>"`). Recognized by [restoreProgressForBook] so a row
         * this app wrote is a row this app can restore.
         */
        private const val READIUM_PREFIX = "readium:"

        private const val PDF_PAGE_PREFIX = "pdfpage:"
        private const val PDF_FALLBACK_HREF = "document.pdf"
        private const val EPUB_CFI_PREFIX = "epubcfi("
    }

    fun onReadiumLocatorChanged(locator: Locator) {
        val currentState = state.value
        val publication = currentState.readiumPublication
        val chapters = currentState.chapters
        val computedListIndex = resolveChapterListIndex(locator, publication, chapters)
        val newIndex = computedListIndex ?: currentState.currentChapterIndex
        val previousHref = currentState.readiumLocator?.href
        if (previousHref != null && previousHref != locator.href) {
            onSelectionCleared()
        }
        val totalProgression = locator.locations.totalProgression?.toFloat() ?: 0f
        val progressPercent = (totalProgression * 100f).coerceIn(0f, 100f)
        state.update {
            it.copy(
                readiumLocator = locator,
                currentChapterIndex = newIndex,
                progressPercent = progressPercent,
            )
        }
        updateProgressDisplay()
        if (!mayPersist(locator)) return
        val bookId = state.value.selectedBookId ?: return
        val locatorJson = CfiMigrator.locatorToJson(locator)
        scope.launch(mainDispatcher) {
            updateReadingProgressUseCase(
                bookId = bookId,
                userId = activeUser.requireUserId(),
                cfiLocation = "readium:${locator.href}",
                percentage = progressPercent,
                locatorJson = locatorJson,
            )
        }
        DebugDual.log(DebugEvent.ProgressEmit(bookId, progressPercent, "locatorChanged"))
    }

    /**
     * Whether this emission may be written to the reader's saved position.
     *
     * ## Why this gate exists
     *
     * The navigator's `currentLocator` is a `StateFlow`, so its FIRST emission is
     * the position the navigator happens to be resting on the moment it is
     * committed — the reading order's first resource, i.e. the COVER at 0%. That
     * emission arrives one frame plus `FRAGMENT_COMMIT_MS` after a book loads,
     * and before the restored position has been applied. Persisting it replaced
     * the reader's real position with the cover: tapping "Continue reading"
     * destroyed the very row it was meant to restore.
     *
     * ## Why it is keyed on the LOADED row and not on a load milestone
     *
     * Arming when the loader finishes is measurably WRONG: the load completes
     * before Compose commits the navigator fragment, so the cover emission still
     * arrives after the gate opened.
     *
     * It is also not keyed on `state.readingProgress`: that slice is filled by
     * [restoreProgressForBook]'s asynchronous Room collector, which may not have
     * delivered by the time the navigator emits. Relying on it opened the gate
     * for the cover emission and the row was destroyed on device.
     *
     * [openOnSavedPosition] is therefore called by the loader with the row it read
     * SYNCHRONOUSLY and used to build the reader's initial locator — the exact
     * position this session is protecting.
     *
     * ## The two suppressions
     *
     * - An emission equal to the protected position IS the restore landing. It is
     *   persisted, and it arms the gate for the rest of the session.
     * - An emission different from it, while it is still unapplied, is the
     *   navigator's pre-restore resting place. It is not persisted.
     *
     * A reader who genuinely sits on page one has no saved position, so
     * [openOnSavedPosition] is never called with one, the gate is open from the
     * start, and their page-one position is persisted normally. Nothing here
     * special-cases a cover href.
     */
    private fun mayPersist(locator: Locator): Boolean {
        if (state.value.progressPersistenceArmed) return true
        val protected = protectedPosition ?: return true
        if (!isSamePosition(locator, protected)) return false
        state.update { it.copy(progressPersistenceArmed = true) }
        return true
    }

    /**
     * Hands the tracker the position this session opened at, read synchronously
     * by the loader. `null` when the book has no saved position — see [mayPersist].
     */
    fun openOnSavedPosition(saved: ReadingProgress?) {
        protectedPosition = saved
        state.update { it.copy(progressPersistenceArmed = saved == null) }
    }

    /**
     * True when [locator] is the position [saved] records. Compares the resource
     * href, because that is what the persisted row keys on (`"readium:<href>"`),
     * normalised the same way chapter matching does.
     */
    private fun isSamePosition(
        locator: Locator,
        saved: ReadingProgress,
    ): Boolean {
        val savedHref = saved.cfiLocation.removePrefix(READIUM_PREFIX)
        val locatorHref = locator.href.toString()
        if (savedHref.isBlank() || locatorHref.isBlank()) return saved.cfiLocation.isBlank()
        return normalizeChapterFile(locatorHref) == normalizeChapterFile(savedHref)
    }

    /**
     * Records that the reader has navigated deliberately (a chapter jump, a
     * scrub, a tap zone), which is a position the reader chose rather than the
     * navigator's pre-restore resting place. Opens the gate.
     */
    fun onReaderMoved() {
        if (!state.value.progressPersistenceArmed) {
            state.update { it.copy(progressPersistenceArmed = true) }
        }
    }

    fun onReadiumViewportChanged(
        height: Int,
        width: Int = 0,
    ) {
        val hasChanged = state.value.readiumViewportHeight != height || state.value.readiumViewportWidth != width
        state.update { it.copy(readiumViewportHeight = height, readiumViewportWidth = width) }
        typographySnapshot?.let { snap ->
            typographySnapshot =
                snap.copy(
                    viewportW = width.takeIf { it > 0 } ?: snap.viewportW,
                    viewportH = height.takeIf { it > 0 } ?: snap.viewportH,
                )
        }
        if (hasChanged) updateProgressDisplay()
    }

    fun onTypographyConfigChanged(
        fontSizeSp: Float,
        lineHeight: Float,
        pageMarginsDp: Float = 16f,
        density: Float = 3f,
    ) {
        densitySnapshot = density
        val vp = state.value
        typographySnapshot =
            ReadingProgressCalculator.ViewportTypography(
                viewportW = vp.readiumViewportWidth.takeIf { it > 0 } ?: 360,
                viewportH = vp.readiumViewportHeight.takeIf { it > 0 } ?: 720,
                fontSizeSp = fontSizeSp,
                lineHeight = lineHeight,
                pageMarginsDp = pageMarginsDp,
                density = density,
            )
        updateProgressDisplay()
    }

    @Deprecated("Use onTypographyConfigChanged")
    fun onTypographyChanged() {
        updateProgressDisplay()
    }

    fun updateProgress(
        bookId: String,
        cfiLocation: String,
        percentage: Float,
    ) {
        scope.launch(mainDispatcher) {
            updateReadingProgressUseCase(
                bookId = bookId,
                userId = activeUser.requireUserId(),
                cfiLocation = cfiLocation,
                percentage = percentage.coerceIn(0f, 100f),
            )
        }
    }

    fun restoreProgressForBook(bookId: String) {
        observeProgressJob?.cancel()
        state.update { it.copy(selectedBookId = bookId, isLoading = true) }
        observeProgressJob =
            scope.launch(mainDispatcher) {
                readerRepository.observeProgress(bookId, activeUser.requireUserId()).collect { progress ->
                    state.update { s ->
                        var newState = s.copy(readingProgress = progress, isLoading = false)
                        if (progress != null) {
                            val cfi = progress.cfiLocation
                            if (cfi.startsWith(PDF_PAGE_PREFIX)) {
                                val page = cfi.removePrefix(PDF_PAGE_PREFIX).toIntOrNull()
                                if (page != null) newState = newState.copy(currentPdfPage = page)
                            } else if (cfi.startsWith(EPUB_CFI_PREFIX)) {
                                val chapterMatch = Regex("/6/(\\d+)").find(cfi)
                                val spineIdx =
                                    chapterMatch
                                        ?.groupValues
                                        ?.getOrNull(1)
                                        ?.toIntOrNull()
                                        ?.minus(1)
                                if (spineIdx != null) {
                                    val chapters = newState.chapters
                                    val listPos =
                                        if (chapters.isNotEmpty()) {
                                            chapters.indexOfFirst { it.index == spineIdx }.takeIf { it >= 0 }
                                        } else {
                                            null
                                        }
                                    val resolved = listPos ?: spineIdx
                                    newState = newState.copy(currentChapterIndex = resolved)
                                }
                            } else if (cfi.startsWith(READIUM_PREFIX)) {
                                // The prefix `onReadiumLocatorChanged` writes. Without
                                // this branch a row written by this app is not
                                // recognised as restorable, and the navigator's first
                                // (cover) emission then overwrites it — data loss.
                                val chapterIndex = resolveChapterIndexForReadiumHref(cfi.removePrefix(READIUM_PREFIX), newState)
                                if (chapterIndex != null) {
                                    newState = newState.copy(currentChapterIndex = chapterIndex)
                                }
                            }
                        }
                        newState
                    }
                    updateProgressDisplay()
                }
            }
    }

    fun onProgressChange(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        state.update { it.copy(progressPercent = clamped) }
        if (state.value.selectedBookId == null) return
        // A scrub is the reader choosing a position, so it is never the
        // navigator's pre-restore resting place.
        onReaderMoved()
        val s = state.value
        if (s.totalPdfPages > 0) {
            val pageIndex = ((clamped / 100f) * s.totalPdfPages).toInt().coerceIn(0, s.totalPdfPages - 1)
            if (pageIndex != s.currentPdfPage) {
                state.update { it.copy(currentPdfPage = pageIndex) }
                updatePdfProgress(pageIndex, s.totalPdfPages)
                updateProgressDisplay()
                // emit pdf locator via holder? Caller handles emit; tracker only updates state
            }
        } else if (s.chapters.isNotEmpty()) {
            val chapterIndex = ((clamped / 100f) * s.chapters.size).toInt().coerceIn(0, s.chapters.size - 1)
            if (chapterIndex != s.currentChapterIndex) {
                state.update { it.copy(currentChapterIndex = chapterIndex) }
                updateProgressForChapter(chapterIndex)
            }
        }
    }

    internal fun updateProgressDisplay() {
        val snapshot = state.value
        val percent: Float
        val label: String
        var title = ""
        var pagesLeft: Int? = null
        var resolvedChapterIndex = snapshot.currentChapterIndex

        if (snapshot.totalPdfPages > 0) {
            val current = snapshot.currentPdfPage + 1
            val total = snapshot.totalPdfPages
            percent = ((current.toFloat() / total) * 100f).coerceIn(0f, 100f)
            label = "$current / $total"
        } else if (snapshot.readiumLocator != null) {
            val locator = snapshot.readiumLocator
            val publication = snapshot.readiumPublication
            val chapters = snapshot.chapters
            val computedIndex: Int? = resolveChapterListIndex(locator, publication, chapters)
            if (computedIndex != null && computedIndex != snapshot.currentChapterIndex) {
                resolvedChapterIndex = computedIndex
            }
            val locatorHref = locator.href.toString()
            val expectedTitle = chapters.getOrNull(resolvedChapterIndex)?.title
            val computedTitle = expectedTitle
            val hrefMismatch = publication != null && computedIndex == null && locatorHref.isNotBlank()
            val chapterHref = chapters.getOrNull(resolvedChapterIndex)?.href
            val hrefTitleMismatch =
                chapterHref != null &&
                    locatorHref.isNotBlank() &&
                    chapterHref.substringAfterLast('/').substringBefore('#').lowercase() !=
                    locatorHref.substringAfterLast('/').substringBefore('#').lowercase()
            if (hrefMismatch || hrefTitleMismatch) {
                DebugDual.logFooterMismatch(expectedTitle, locatorHref)
                DebugDual.log(DebugEvent.FooterMismatch(locatorHref, computedTitle, expectedTitle))
            } else if (computedTitle != null) {
                DebugDual.log(DebugEvent.ChapterResolved(locatorHref, computedTitle, resolvedChapterIndex))
            }
            val totalProgression = locator.locations.totalProgression?.toFloat() ?: 0f
            percent = (totalProgression * 100f).coerceIn(0f, 100f)
            // Cleaned for one footer line: TOC leaders, dot runs and file names
            // that stand in for a missing title are dropped (cleanChapterTitle).
            val chapterTitle =
                cleanChapterTitle(chapters.getOrNull(resolvedChapterIndex)?.title)
                    ?: cleanChapterTitle(locator.title)
                    ?: ""
            val typography = typographySnapshot
            val viewportTypography =
                typography?.copy(
                    viewportW = snapshot.readiumViewportWidth.takeIf { it > 0 } ?: typography.viewportW,
                    viewportH = snapshot.readiumViewportHeight.takeIf { it > 0 } ?: typography.viewportH,
                    density = densitySnapshot,
                ) ?: ReadingProgressCalculator.ViewportTypography(
                    viewportW = snapshot.readiumViewportWidth.takeIf { it > 0 } ?: 360,
                    viewportH = snapshot.readiumViewportHeight.takeIf { it > 0 } ?: 720,
                    fontSizeSp = 16f,
                    lineHeight = 1.6f,
                    pageMarginsDp = 16f,
                    density = densitySnapshot,
                )
            // Pages left come from the chapter's measured visible text. Until that
            // read lands (one background read per chapter) the count is unknown,
            // and the footer shows nothing rather than a made-up number.
            val chapterChars = chapterTextLength(publication, snapshot.selectedBookId, locatorHref)
            pagesLeft =
                chapterChars?.let {
                    chapterPagesRemaining(
                        chapterTextChars = it,
                        progression = locator.locations.progression ?: 0.0,
                        viewport = viewportTypography,
                    )
                }
            title = chapterTitle
            ensurePositions(publication)
            if (resourceStartFractions != null && snapshot.chapterStartFractions.size != chapters.size) refreshChapterStarts()
            label =
                if (pagesLeft != null && chapterTitle.isNotEmpty()) {
                    application.getString(
                        com.nexo.feature.reader.R.string.reader_pages_remaining,
                        chapterTitle,
                        pagesLeft,
                    )
                } else {
                    chapterTitle
                }
        } else if (snapshot.chapters.isNotEmpty()) {
            val current = snapshot.currentChapterIndex + 1
            val total = snapshot.chapters.size
            percent = ((current.toFloat() / total) * 100f).coerceIn(0f, MAX_PROGRESS_PERCENT)
            label = "$current / $total"
        } else {
            percent = 0f
            label = ""
        }
        state.update {
            it.copy(
                currentChapterIndex = resolvedChapterIndex,
                progressPercent = percent,
                progressLabel = label,
                chapterTitle = title,
                chapterPagesLeft = pagesLeft,
            )
        }
    }

    /**
     * Visible text length of the chapter at [href] for [bookId], or null while it
     * is being measured. The first call for a chapter starts one background read
     * (resource bytes, tags stripped); when it lands the display is recomputed.
     * Keyed by book as well as href: two books may both have `chapter1.xhtml`.
     */
    private fun chapterTextLength(
        publication: Publication?,
        bookId: String?,
        href: String,
    ): Int? {
        if (publication == null || href.isBlank()) return null
        val resourceHref = href.substringBefore('#')
        val key = "${bookId.orEmpty()}|$resourceHref"
        ChapterTextLengthCache.peek(key)?.let { return it }
        if (!ChapterTextLengthCache.claimRead(key)) return null
        scope.launch(Dispatchers.IO) {
            val length =
                runCatching {
                    val url = Url(resourceHref) ?: return@runCatching null
                    val link = publication.linkWithHref(url) ?: return@runCatching null
                    val bytes = publication.get(link)?.read()?.getOrNull() ?: return@runCatching null
                    visibleTextLength(bytes.decodeToString())
                }.getOrNull()
            if (length == null) {
                ChapterTextLengthCache.release(key)
                return@launch
            }
            ChapterTextLengthCache.store(key, length)
            withContext(mainDispatcher) { updateProgressDisplay() }
        }
        return null
    }

    /**
     * Computes Readium's positions for [publication] once, off the main thread:
     * the flat list backs [locatorAtFraction] (exact scrub targets), and the first
     * position of each chapter's resource gives the chapter starts the progress
     * bar draws as ticks and names while scrubbing.
     */
    private fun ensurePositions(publication: Publication?) {
        if (publication == null || positionsFor === publication) return
        positionsFor = publication
        positions = null
        resourceStartFractions = null
        positionsJob?.cancel()
        positionsJob =
            scope.launch(Dispatchers.IO) {
                val byResource = runCatching { publication.positionsByReadingOrder() }.getOrNull() ?: return@launch
                val flat = byResource.flatten()
                val resourceStarts =
                    publication.readingOrder.mapIndexed { index, link ->
                        link.href.toString() to
                            byResource
                                .getOrNull(index)
                                ?.firstOrNull()
                                ?.locations
                                ?.totalProgression
                                ?.toFloat()
                    }
                withContext(mainDispatcher) {
                    if (positionsFor !== publication) return@withContext
                    positions = flat
                    resourceStartFractions = resourceStarts
                    refreshChapterStarts()
                }
            }
    }

    /**
     * Maps the resource starts onto the TOC chapter list. Re-run whenever the two
     * disagree in size, since the chapter list can land after the positions.
     */
    private fun refreshChapterStarts() {
        val resourceStarts = resourceStartFractions ?: return
        val chapters = state.value.chapters
        if (chapters.isEmpty()) return
        val starts =
            chapters.map { chapter ->
                val file = normalizeChapterFile(chapter.href)
                resourceStarts.firstOrNull { (href, _) -> normalizeChapterFile(href) == file }?.second
            }
        state.update { it.copy(chapterStartFractions = starts) }
    }

    /**
     * The Readium position closest to [fraction] of the book, for a scrub that
     * should land where the finger let go instead of at a chapter start. Null
     * until positions are computed; the caller then falls back to the chapter jump.
     */
    fun locatorAtFraction(fraction: Float): Locator? {
        val list = positions ?: return null
        if (list.isEmpty()) return null
        val target = fraction.coerceIn(0f, 1f).toDouble()
        return list.minByOrNull { kotlin.math.abs((it.locations.totalProgression ?: 0.0) - target) }
    }

    /**
     * Resolves the reader's chapter list position for a `readium:<href>` marker.
     *
     * The href is matched with the same normalisation [resolveChapterListIndex]
     * uses (full href first, then path and filename), so a `readium:` row
     * restores the same chapter the equivalent locator would. Returns null when
     * the href matches no chapter — the row is then display-only, never a
     * licence to move the reader somewhere arbitrary.
     */
    private fun resolveChapterIndexForReadiumHref(
        href: String,
        snapshot: ReaderLifecycleState,
    ): Int? {
        val chapters = snapshot.chapters
        if (chapters.isEmpty() || href.isBlank()) return null
        val path = href.substringBefore('#').substringBefore('?')
        chapters
            .indexOfFirst { it.href == href }
            .takeIf { it >= 0 }
            ?.let { return it }
        chapters
            .indexOfFirst { it.href.substringBefore('#').substringBefore('?') == path }
            .takeIf { it >= 0 }
            ?.let { return it }
        chapters
            .indexOfFirst { normalizeChapterFile(it.href) == normalizeChapterFile(href) }
            .takeIf { it >= 0 }
            ?.let { return it }
        snapshot.readiumPublication?.let { publication ->
            try {
                val link = publication.linkWithHref(Url(href) ?: return@let null) ?: return@let null
                val roIndex = publication.readingOrder.indexOf(link)
                if (roIndex >= 0) {
                    val byIndex = chapters.indexOfFirst { it.index == roIndex }.takeIf { it >= 0 }
                    if (byIndex != null) return byIndex
                    val firstIdx = chapters.minOfOrNull { it.index } ?: 0
                    (roIndex - firstIdx).takeIf { it in chapters.indices }?.let { return it }
                    roIndex.takeIf { it in chapters.indices }?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun normalizeChapterFile(href: String): String =
        href
            .substringAfterLast('/')
            .substringBefore('#')
            .substringBefore('?')
            .trim()
            .lowercase()

    private fun resolveChapterListIndex(
        locator: Locator,
        publication: Publication?,
        chapters: List<BookChapter>,
    ): Int? {
        if (chapters.isEmpty()) return null
        val locatorHref = locator.href.toString()
        val normLocatorFile = normalizeChapterFile(locatorHref)
        chapters.indexOfFirst { it.href == locatorHref }.takeIf { it >= 0 }?.let { return it }
        val locatorBase = locatorHref.substringBefore('#').substringBefore('?')
        chapters
            .indexOfFirst { it.href.substringBefore('#').substringBefore('?') == locatorBase }
            .takeIf { it >= 0 }
            ?.let { return it }
        chapters
            .indexOfFirst { normalizeChapterFile(it.href) == normLocatorFile }
            .takeIf { it >= 0 }
            ?.let { return it }
        publication?.let { pub ->
            try {
                val link = pub.linkWithHref(locator.href) ?: return@let null
                val roIndex = pub.readingOrder.indexOf(link)
                if (roIndex >= 0) {
                    chapters.indexOfFirst { it.index == roIndex }.takeIf { it >= 0 }?.let { return it }
                    val firstIdx = chapters.minOfOrNull { it.index } ?: 0
                    val adjusted = roIndex - firstIdx
                    if (adjusted in chapters.indices) return adjusted
                    if (roIndex in chapters.indices) return roIndex
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun updatePdfProgress(
        currentPage: Int,
        totalPages: Int,
        locatorJson: String? = null,
    ) {
        val bookId = state.value.selectedBookId ?: return
        if (totalPages > 0) {
            val percentage = (((currentPage + 1).toFloat() / totalPages) * 100f).coerceIn(0f, 100f)
            val cfiLocation = "pdfpage:$currentPage"
            scope.launch(mainDispatcher) {
                updateReadingProgressUseCase(
                    bookId = bookId,
                    userId = activeUser.requireUserId(),
                    cfiLocation = cfiLocation,
                    percentage = percentage,
                    locatorJson = locatorJson,
                )
            }
        }
    }

    /**
     * The PDF.js viewer reports where it already is. Unlike the page setters this
     * neither arms the persistence gate nor navigates, or scrolling would fight itself.
     */
    fun onPdfPageReported(
        pageIndex: Int,
        totalPages: Int,
    ) {
        if (totalPages <= 0 || pageIndex !in 0 until totalPages) return
        if (state.value.currentPdfPage == pageIndex && state.value.totalPdfPages == totalPages) return
        state.update { it.copy(currentPdfPage = pageIndex, totalPdfPages = totalPages) }
        updateProgressDisplay()
        updatePdfProgress(pageIndex, totalPages, pdfLocatorJson(pageIndex, totalPages))
    }

    private fun pdfLocatorJson(
        pageIndex: Int,
        totalPages: Int,
    ): String {
        val href =
            state.value.readiumPublication
                ?.readingOrder
                ?.getOrNull(pageIndex)
                ?.href
                ?.toString()
                ?: PDF_FALLBACK_HREF
        return JSONObject()
            .put("href", href)
            .put("type", "application/pdf")
            .put(
                "locations",
                JSONObject()
                    .put("position", pageIndex + 1)
                    .put("totalProgression", pageIndex.toDouble() / totalPages),
            ).toString()
    }

    private fun updateProgressForChapter(chapterIndex: Int) {
        val bookId = state.value.selectedBookId ?: return
        val totalChapters = state.value.chapters.size
        if (totalChapters > 0) {
            val percentage = (((chapterIndex + 1).toFloat() / totalChapters) * 100f).coerceIn(0f, MAX_PROGRESS_PERCENT)
            val chapter = state.value.chapters.getOrNull(chapterIndex)
            val spineIndex = chapter?.let { it.index + 1 } ?: (chapterIndex + 1)
            val cfiLocation = "epubcfi(/6/$spineIndex)"
            scope.launch(mainDispatcher) {
                updateReadingProgressUseCase(bookId = bookId, userId = activeUser.requireUserId(), cfiLocation = cfiLocation, percentage = percentage)
            }
        }
    }

    override fun onCleared() {
        observeProgressJob?.cancel()
        positionsJob?.cancel()
    }
}

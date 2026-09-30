package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import com.nexo.presentation.viewmodel.reader.ReadingProgressCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Wraps ReadingProgressCalculator and handles locator/viewport/typography progress.
 *
 * Preserves verbatim: reflection chain (getPositions -> field -> members) lives
 * inside ReadingProgressCalculator; display vs persisted split; restoreProgressForBook.
 */
class ReadingProgressTracker(
    private val application: Application,
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val readerRepository: ReaderRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgressUseCase,
    private val onSelectionCleared: () -> Unit = {},
    private val onErrorEvent: (UiEvent) -> Unit = {},
) : Clearable {
    private var observeProgressJob: Job? = null
    private var typographySnapshot: ReadingProgressCalculator.ViewportTypography? = null
    private var densitySnapshot: Float = 3f

    companion object {
        private const val MAX_PROGRESS_PERCENT = 99f
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
        val bookId = state.value.selectedBookId ?: return
        val locatorJson = CfiMigrator.locatorToJson(locator)
        scope.launch(mainDispatcher) {
            updateReadingProgressUseCase(
                bookId = bookId,
                cfiLocation = "readium:${locator.href}",
                percentage = progressPercent,
                locatorJson = locatorJson,
            )
        }
        DebugDual.log(DebugEvent.ProgressEmit(bookId, progressPercent, "locatorChanged"))
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
                readerRepository.observeProgress(bookId).collect { progress ->
                    state.update { s ->
                        var newState = s.copy(readingProgress = progress, isLoading = false)
                        if (progress != null) {
                            val cfi = progress.cfiLocation
                            if (cfi.startsWith("pdfpage:")) {
                                val page = cfi.removePrefix("pdfpage:").toIntOrNull()
                                if (page != null) newState = newState.copy(currentPdfPage = page)
                            } else if (cfi.startsWith("epubcfi(")) {
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
            val chapterTitle =
                chapters.getOrNull(resolvedChapterIndex)?.title?.takeIf { it.isNotBlank() }
                    ?: locatorHref.substringAfterLast('/').substringBefore('#').takeIf { it.isNotBlank() }
                    ?: "—"
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
            val calc =
                ReadingProgressCalculator.compute(
                    publication = publication,
                    locator = locator,
                    chapters = chapters,
                    currentChapterIndex = resolvedChapterIndex,
                    viewport = viewportTypography,
                )
            val remaining = calc.remaining
            label =
                if (remaining > 0 && chapterTitle != "—") {
                    application.getString(
                        com.nexo.R.string.reader_pages_remaining,
                        chapterTitle,
                        remaining,
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
        state.update { it.copy(currentChapterIndex = resolvedChapterIndex, progressPercent = percent, progressLabel = label) }
    }

    private fun resolveChapterListIndex(
        locator: Locator,
        publication: Publication?,
        chapters: List<BookChapter>,
    ): Int? {
        if (chapters.isEmpty()) return null
        val locatorHref = locator.href.toString()

        fun normalizeFile(href: String): String =
            href
                .substringAfterLast('/')
                .substringBefore('#')
                .substringBefore('?')
                .trim()
                .lowercase()
        val normLocatorFile = normalizeFile(locatorHref)
        chapters.indexOfFirst { it.href == locatorHref }.takeIf { it >= 0 }?.let { return it }
        val locatorBase = locatorHref.substringBefore('#').substringBefore('?')
        chapters
            .indexOfFirst { it.href.substringBefore('#').substringBefore('?') == locatorBase }
            .takeIf { it >= 0 }
            ?.let { return it }
        chapters
            .indexOfFirst { normalizeFile(it.href) == normLocatorFile }
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
    ) {
        val bookId = state.value.selectedBookId ?: return
        if (totalPages > 0) {
            val percentage = (((currentPage + 1).toFloat() / totalPages) * 100f).coerceIn(0f, 100f)
            val cfiLocation = "pdfpage:$currentPage"
            scope.launch(mainDispatcher) {
                updateReadingProgressUseCase(bookId = bookId, cfiLocation = cfiLocation, percentage = percentage)
            }
        }
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
                updateReadingProgressUseCase(bookId = bookId, cfiLocation = cfiLocation, percentage = percentage)
            }
        }
    }

    override fun onCleared() {
        observeProgressJob?.cancel()
    }
}

package com.nexo.presentation.viewmodel.reader.lifecycle

import androidx.annotation.VisibleForTesting
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * Chapter/PDF navigation: goToChapter, goToNext/Previous, goToPdfPage, onTapZone,
 * emitLocatorForCurrentState, spineIndexToListPosition — with clamp guards preserved verbatim.
 */
class ReadingNavigator(
    private val state: MutableStateFlow<ReaderLifecycleState>,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val readerRepository: ReaderRepository? = null,
    private val updateReadingProgressUseCase: UpdateReadingProgressUseCase? = null,
    private val onChapterChanged: () -> Unit = {},
    private val onNavigateToLocator: (Locator) -> Unit = {},
    private val onSelectionCleared: () -> Unit = {},
    private val onProgressDisplay: () -> Unit = {},
) : Clearable {
    companion object {
        private const val MAX_PROGRESS_PERCENT = 99f
    }

    fun goToNextChapter() {
        val currentIndex = state.value.currentChapterIndex
        val totalChapters = state.value.chapters.size
        if (currentIndex < totalChapters - 1) {
            val newIndex = currentIndex + 1
            state.update { it.copy(currentChapterIndex = newIndex) }
            updateProgressForChapter(newIndex)
            onChapterChanged()
            navigateToChapter(newIndex)
        }
    }

    fun goToPreviousChapter() {
        val currentIndex = state.value.currentChapterIndex
        if (currentIndex > 0) {
            val newIndex = currentIndex - 1
            state.update { it.copy(currentChapterIndex = newIndex) }
            updateProgressForChapter(newIndex)
            onChapterChanged()
            navigateToChapter(newIndex)
        }
    }

    fun goToChapter(listPosition: Int) {
        if (listPosition in state.value.chapters.indices) {
            if (listPosition == state.value.currentChapterIndex) return
            state.update { it.copy(currentChapterIndex = listPosition) }
            updateProgressForChapter(listPosition)
            onChapterChanged()
            navigateToChapter(listPosition)
        }
    }

    fun spineIndexToListPosition(
        spineIndex: Int,
        chapters: List<BookChapter>,
    ): Int? {
        chapters.indexOfFirst { it.index == spineIndex }.takeIf { it >= 0 }?.let { return it }
        return null
    }

    private fun navigateToChapter(listPosition: Int) {
        val currentState = state.value
        val publication = currentState.readiumPublication ?: return
        val chapters = currentState.chapters
        val link: Link? =
            when {
                listPosition in chapters.indices -> {
                    val chapter = chapters[listPosition]
                    val hrefBase = chapter.href.substringBefore('#').substringBefore('?')
                    val normFile = hrefBase.substringAfterLast('/').lowercase()
                    publication.readingOrder.firstOrNull {
                        it.href
                            .toString()
                            .substringAfterLast('/')
                            .substringBefore('#')
                            .substringBefore('?')
                            .lowercase() == normFile
                    } ?: publication.readingOrder.getOrNull(chapter.index)
                }
                else -> publication.readingOrder.getOrNull(listPosition)
            } ?: return
        val roIndex = publication.readingOrder.indexOf(link).takeIf { it >= 0 } ?: listPosition
        val total = publication.readingOrder.size.coerceAtLeast(1)
        val totalProgression = (roIndex.toFloat() / total).coerceIn(0f, 1f)
        emitEpubNavigateLocator(listPosition, totalProgression, link)
    }

    fun goToNextPdfPage() {
        val currentPage = state.value.currentPdfPage
        val totalPages = state.value.totalPdfPages
        if (currentPage < totalPages - 1) {
            val newPage = currentPage + 1
            state.update { it.copy(currentPdfPage = newPage) }
            updatePdfProgress(newPage, totalPages)
            onProgressDisplay()
        }
    }

    fun goToPreviousPdfPage() {
        val currentPage = state.value.currentPdfPage
        if (currentPage > 0) {
            val newPage = currentPage - 1
            state.update { it.copy(currentPdfPage = newPage) }
            updatePdfProgress(newPage, state.value.totalPdfPages)
            onProgressDisplay()
        }
    }

    fun goToPdfPage(pageIndex: Int) {
        val totalPages = state.value.totalPdfPages
        if (pageIndex in 0 until totalPages) {
            state.update { it.copy(currentPdfPage = pageIndex) }
            updatePdfProgress(pageIndex, totalPages)
            onProgressDisplay()
        }
    }

    fun goToPage(pageNumber: Int) {
        val totalPages = state.value.totalPdfPages
        if (pageNumber in 1..totalPages) {
            val newPage = pageNumber - 1
            state.update { it.copy(currentPdfPage = newPage) }
            updatePdfProgress(newPage, totalPages)
            onProgressDisplay()
        }
    }

    fun onTapZone(isLeftZone: Boolean) {
        onSelectionCleared()
        val format = state.value.bookFormat
        when (format) {
            "pdf" -> if (isLeftZone) goToPreviousPdfPage() else goToNextPdfPage()
            else -> if (isLeftZone) goToPreviousChapter() else goToNextChapter()
        }
        emitLocatorForCurrentState()
    }

    fun emitLocatorForCurrentState() {
        val currentState = state.value
        val publication = currentState.readiumPublication ?: return
        when (currentState.bookFormat) {
            "pdf" -> {
                val link = publication.readingOrder.getOrNull(currentState.currentPdfPage) ?: return
                emitPdfNavigateLocator(currentState.currentPdfPage, link)
            }
            else -> {
                val chapters = currentState.chapters
                val link: Link =
                    when {
                        currentState.currentChapterIndex in chapters.indices -> {
                            val chapter = chapters[currentState.currentChapterIndex]
                            val hrefBase = chapter.href.substringBefore('#').substringBefore('?')
                            val normFile = hrefBase.substringAfterLast('/').lowercase()
                            publication.readingOrder.firstOrNull {
                                it.href
                                    .toString()
                                    .substringAfterLast('/')
                                    .substringBefore('#')
                                    .substringBefore('?')
                                    .lowercase() == normFile
                            } ?: publication.readingOrder.getOrNull(chapter.index)
                                ?: publication.readingOrder.getOrNull(currentState.currentChapterIndex)
                        }
                        else -> publication.readingOrder.getOrNull(currentState.currentChapterIndex)
                    } ?: return
                val roIndex = publication.readingOrder.indexOf(link).takeIf { it >= 0 } ?: currentState.currentChapterIndex
                val totalProgression =
                    if (publication.readingOrder.isNotEmpty()) {
                        roIndex.toFloat() / publication.readingOrder.size
                    } else {
                        0f
                    }
                emitEpubNavigateLocator(currentState.currentChapterIndex, totalProgression, link)
            }
        }
    }

    private fun emitPdfNavigateLocator(
        pageIndex: Int,
        link: Link? = null,
    ) {
        val publication = state.value.readiumPublication ?: return
        val resolvedLink = link ?: publication.readingOrder.getOrNull(pageIndex) ?: return
        val json =
            JSONObject().apply {
                put("href", resolvedLink.href.toString())
                put("mediaType", resolvedLink.mediaType?.toString() ?: "application/pdf")
                put("locations", JSONObject().apply { put("position", pageIndex + 1) })
            }
        val locator = Locator.fromJSON(json) ?: return
        scope.launch(mainDispatcher) { onNavigateToLocator(locator) }
    }

    private fun emitEpubNavigateLocator(
        chapterIndex: Int,
        totalProgression: Float,
        link: Link? = null,
    ) {
        val publication = state.value.readiumPublication ?: return
        val resolvedLink = link ?: publication.readingOrder.getOrNull(chapterIndex) ?: return
        val json =
            JSONObject().apply {
                put("href", resolvedLink.href.toString())
                put("type", resolvedLink.mediaType?.toString() ?: "application/xhtml+xml")
                put(
                    "locations",
                    JSONObject().apply {
                        put("progression", 0.0)
                        put("totalProgression", totalProgression.toDouble().coerceIn(0.0, 1.0))
                    },
                )
            }
        val locator = Locator.fromJSON(json) ?: return
        scope.launch(mainDispatcher) { onNavigateToLocator(locator) }
    }

    private fun updatePdfProgress(
        currentPage: Int,
        totalPages: Int,
    ) {
        val bookId = state.value.selectedBookId ?: return
        if (totalPages > 0) {
            val percentage = (((currentPage + 1).toFloat() / totalPages) * 100f).coerceIn(0f, 100f)
            val cfiLocation = "pdfpage:$currentPage"
            val useCase = updateReadingProgressUseCase ?: return
            scope.launch(mainDispatcher) {
                useCase(bookId = bookId, cfiLocation = cfiLocation, percentage = percentage)
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
            val useCase = updateReadingProgressUseCase ?: return
            scope.launch(mainDispatcher) {
                useCase(bookId = bookId, cfiLocation = cfiLocation, percentage = percentage)
            }
        }
    }

    override fun onCleared() {}

    // ── Pending CFI after load ──────────────────────────────────────
    // SDD reader-facade-split, T5: moved verbatim from ReaderViewModel so
    // post-load navigation lives in the lifecycle owner, not the VM.

    /**
     * Pending CFI range to navigate to once the book finishes loading.
     * Set by [navigateToCfiAfterLoad] (called from NavHost before navigation),
     * consumed in [applyPendingCfi] after the book reports ready.
     * Cleared after application or on ViewModel destruction.
     */
    @VisibleForTesting
    internal var pendingCfiAfterLoad: String? = null

    /**
     * Stores a CFI range that should be navigated to once the book finishes
     * loading. If the book is already loaded (same-book highlight tap),
     * applies the pending CFI immediately instead of waiting for loadBook.
     */
    fun navigateToCfiAfterLoad(cfiRange: String) {
        pendingCfiAfterLoad = cfiRange
        // If the book is already loaded (same-book highlight tap), apply
        // the pending CFI immediately instead of waiting for loadBook.
        val state = state.value
        if (!state.isLoading && state.readiumPublication != null) {
            applyPendingCfi()
        }
    }

    /**
     * Applies the pending CFI navigation (if any) after the book has
     * finished loading. Uses the same logic as highlight navigation:
     * - PDF: `cfiRange` is `"pdfpage:<N>"` → [goToPdfPage]
     * - EPUB: extract chapter index from CFI via `Regex("/6/(\\d+)")` → [goToChapter]
     *
     * The pending CFI is cleared after application so it does not re-fire
     * on subsequent book loads.
     */
    @VisibleForTesting
    internal fun applyPendingCfi() {
        val cfiRange = pendingCfiAfterLoad ?: return
        pendingCfiAfterLoad = null

        if (cfiRange.startsWith("pdfpage:")) {
            val page = cfiRange.removePrefix("pdfpage:").toIntOrNull()
            if (page != null) goToPdfPage(page)
        } else {
            val chapterMatch = Regex("/6/(\\d+)").find(cfiRange)
            val spineIndex =
                chapterMatch
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.minus(1)
            if (spineIndex != null) {
                val chapters = state.value.chapters
                val listPos = chapters.indexOfFirst { it.index == spineIndex }.takeIf { it >= 0 }
                if (listPos != null) goToChapter(listPos) else goToChapter(spineIndex.coerceIn(chapters.indices))
            }
        }
    }
}

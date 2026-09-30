package com.nexo.presentation.viewmodel.reader.interaction

import android.util.Log
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/**
 * Highlights CRUD and observation. Owns cancellable observeHighlights Job.
 * Locator fallback: (_state.selectionState as? New)?.locator ?: param preserved verbatim.
 */
internal class HighlightManager(
    private val store: InteractionStateStore,
    private val selectionManager: SelectionManager,
    private val readerRepository: ReaderRepository,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    companion object {
        private const val TAG = "HighlightManager"
    }

    private var observeHighlightsJob: Job? = null
    private var observeBookmarksJobRef: Job? = null // not used here; bookmarks in BookmarkManager

    fun observeBook(bookId: String) {
        DebugLog.info(TAG, "observeBook called for bookId=$bookId")
        observeHighlightsJob?.cancel()
        observeHighlightsJob =
            scope.launch(mainDispatcher) {
                DebugLog.info(TAG, "observeHighlights collect started for bookId=$bookId")
                readerRepository.observeHighlights(bookId).collect { highlights ->
                    DebugLog.info(TAG, "observeHighlights emitted ${highlights.size} highlights")
                    store.update { it.copy(highlights = highlights) }
                }
            }
    }

    fun testStopObserving() {
        observeHighlightsJob?.cancel()
    }

    fun createHighlight(
        bookId: String,
        cfiRange: String,
        textContent: String,
        note: String? = null,
        color: String = HighlightColor.YELLOW.hex,
        locatorJson: String? = null,
    ) {
        scope.launch(mainDispatcher) {
            val highlight =
                Highlight(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    cfiRange = cfiRange,
                    textContent = textContent,
                    note = note,
                    color = color,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    deletedAtEpochMillis = null,
                    locatorJson = locatorJson,
                )
            readerRepository.upsertHighlight(highlight)
            Log.d(TAG, "Highlight created: ${highlight.id}")
        }
    }

    fun onReadiumHighlightColorSelected(
        color: String,
        selectedBookId: String?,
        readiumSelectionLocator: Locator?,
        selectedText: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        currentChapterIndex: Int,
    ) {
        val bookId = selectedBookId ?: return
        val locator =
            (store.value.selectionState as? ReaderSelectionState.New)?.locator
                ?: readiumSelectionLocator
                ?: return
        val text = selectedText ?: store.value.selectedText ?: return
        val activeId = selectionManager.activeHighlightId()
        DebugLog.info(TAG, "Color selected: $color for id=${activeId ?: "<new>"}")
        if (activeId != null) {
            onReadiumUpdateHighlightColor(activeId, color)
            selectionManager.dismissMenuAndClearSelection()
            return
        }
        val locatorJson = CfiMigrator.locatorToJson(locator)
        createHighlight(
            bookId = bookId,
            cfiRange = "readium:${locator.href}",
            textContent = text,
            color = color,
            locatorJson = locatorJson,
        )
        DebugLog.info(TAG, "Color selected: $color, menu closed")
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onReadiumDeleteHighlight(highlightId: String) {
        val existing = store.value.highlights.find { it.id == highlightId } ?: return
        val updated = existing.copy(deletedAtEpochMillis = System.currentTimeMillis(), updatedAtEpochMillis = System.currentTimeMillis())
        scope.launch(mainDispatcher) { readerRepository.upsertHighlight(updated) }
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onReadiumUpdateHighlightColor(
        highlightId: String,
        color: String,
    ) {
        val existing = store.value.highlights.find { it.id == highlightId } ?: return
        val updated = existing.copy(color = color, updatedAtEpochMillis = System.currentTimeMillis())
        scope.launch(mainDispatcher) { readerRepository.upsertHighlight(updated) }
    }

    fun onSelectHighlightColor(
        color: String,
        selectedBookId: String?,
        readiumSelectionLocator: Locator?,
        selectedText: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        currentChapterIndex: Int,
    ) {
        val activeId = selectionManager.activeHighlightId()
        if (activeId != null) {
            onReadiumUpdateHighlightColor(activeId, color)
        } else {
            val bookId = selectedBookId ?: return
            val text = selectedText ?: return
            val cfiRange = if (bookFormat == "pdf") "pdfpage:$currentPdfPage" else "epubcfi(/6/${currentChapterIndex + 1})"
            createHighlight(bookId = bookId, cfiRange = cfiRange, textContent = text, color = color)
        }
        selectionManager.dismissMenuAndClearSelection()
    }

    override fun onCleared() {
        observeHighlightsJob?.cancel()
    }
}

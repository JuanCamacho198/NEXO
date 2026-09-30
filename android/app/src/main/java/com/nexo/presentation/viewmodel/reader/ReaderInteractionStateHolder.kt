package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.VisibleForTesting
import com.nexo.domain.model.Highlight
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.reader.interaction.AnnotationManager
import com.nexo.presentation.viewmodel.reader.interaction.BookmarkManager
import com.nexo.presentation.viewmodel.reader.interaction.HighlightManager
import com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore
import com.nexo.presentation.viewmodel.reader.interaction.SelectionManager
import com.nexo.presentation.viewmodel.reader.interaction.ShareDictionaryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.readium.r2.shared.publication.Locator

/**
 * Facade that delegates to 5 interaction managers behind retained ReaderInteractionStateHolder API.
 * Single MutableStateFlow owned here, shared via InteractionStateStore for atomic dismiss.
 * Preserves public surface verbatim; no behavior change. Marked @Deprecated for removal in PR #3.
 */
@Deprecated("Use managers directly")
class ReaderInteractionStateHolder(
    private val readerRepository: ReaderRepository,
    private val dictionaryRepository: DictionaryRepository?,
    private val scope: CoroutineScope,
    private val onEvent: (UiEvent) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val _state = MutableStateFlow(ReaderInteractionState())
    val state: StateFlow<ReaderInteractionState> = _state.asStateFlow()

    private val _clearSelectionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearSelectionEvent: SharedFlow<Unit> = _clearSelectionEvent.asSharedFlow()

    private val store = InteractionStateStore(_state, _clearSelectionEvent)
    private val selectionManager = SelectionManager(store, scope, mainDispatcher)
    private val highlightManager = HighlightManager(store, selectionManager, readerRepository, scope, mainDispatcher)
    private val annotationManager = AnnotationManager(store, selectionManager, readerRepository, scope, mainDispatcher)
    private val bookmarkManager = BookmarkManager(store, readerRepository, scope, mainDispatcher)
    private val shareDictionaryManager =
        ShareDictionaryManager(store, selectionManager, dictionaryRepository, scope, onEvent, mainDispatcher)

    init {
        store.setCoordinator = { selectionManager.coordinator = it }
    }

    // ── Testing ───────────────────────────────────────────────────
    @VisibleForTesting
    internal fun testSetInitialHighlights(highlights: List<Highlight>) {
        _state.update { it.copy(highlights = highlights) }
    }

    @VisibleForTesting
    internal fun testStopObserving() {
        highlightManager.testStopObserving()
        bookmarkManager.testStopObserving()
    }

    // ── Book observation ──────────────────────────────────────────
    fun observeBook(bookId: String) {
        highlightManager.observeBook(bookId)
        bookmarkManager.observeBook(bookId)
    }

    // ── Selection pipeline ────────────────────────────────────────
    fun onReadiumSelection(
        locator: Locator,
        rect: RectF,
        text: String,
        existingHighlights: List<Highlight>,
    ) = selectionManager.onReadiumSelection(locator, rect, text, existingHighlights)

    fun onSelectionCleared() = selectionManager.onSelectionCleared()

    fun onSelectionCleared(
        currentActiveHighlightId: String?,
        currentHighlightTapDebounceUntil: Long,
    ) = selectionManager.onSelectionCleared(currentActiveHighlightId, currentHighlightTapDebounceUntil)

    fun onTextSelectionEvent(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = selectionManager.onTextSelectionEvent(text, left, top, right, bottom)

    fun onTextSelection(
        text: String,
        rect: Rect,
    ) = selectionManager.onTextSelection(text, rect)

    fun onHighlightTapped(
        highlight: Highlight,
        rect: RectF,
    ) = selectionManager.onHighlightTapped(highlight, rect)

    // ── Highlights CRUD ───────────────────────────────────────────
    fun createHighlight(
        bookId: String,
        cfiRange: String,
        textContent: String,
        note: String? = null,
        color: String = com.nexo.domain.model.HighlightColor.YELLOW.hex,
        locatorJson: String? = null,
    ) = highlightManager.createHighlight(bookId, cfiRange, textContent, note, color, locatorJson)

    fun onReadiumHighlightColorSelected(
        color: String,
        selectedBookId: String?,
        readiumSelectionLocator: Locator?,
        selectedText: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        currentChapterIndex: Int,
    ) = highlightManager.onReadiumHighlightColorSelected(
        color,
        selectedBookId,
        readiumSelectionLocator,
        selectedText,
        bookFormat,
        currentPdfPage,
        currentChapterIndex,
    )

    fun onReadiumDeleteHighlight(highlightId: String) = highlightManager.onReadiumDeleteHighlight(highlightId)

    fun onReadiumUpdateHighlightColor(
        highlightId: String,
        color: String,
    ) = highlightManager.onReadiumUpdateHighlightColor(highlightId, color)

    fun onSelectHighlightColor(
        color: String,
        selectedBookId: String?,
        readiumSelectionLocator: Locator?,
        selectedText: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        currentChapterIndex: Int,
    ) = highlightManager.onSelectHighlightColor(
        color,
        selectedBookId,
        readiumSelectionLocator,
        selectedText,
        bookFormat,
        currentPdfPage,
        currentChapterIndex,
    )

    // ── Annotations ───────────────────────────────────────────────
    fun onShowNoteModal() = annotationManager.onShowNoteModal()

    fun onDismissNoteModal() = annotationManager.onDismissNoteModal()

    fun onSaveNote(text: String) = annotationManager.onSaveNote(text)

    fun onAnnotate(
        selectedBookId: String?,
        bookFormat: String?,
        currentChapterIndex: Int,
        currentPdfPage: Int,
        chapters: List<BookChapter>,
    ) = annotationManager.onAnnotate(selectedBookId, bookFormat, currentChapterIndex, currentPdfPage, chapters)

    // ── Tag ───────────────────────────────────────────────────────
    fun onShowTagInput() = annotationManager.onShowTagInput()

    fun onDismissTagInput() = annotationManager.onDismissTagInput()

    fun onTagTextChanged(text: String) = annotationManager.onTagTextChanged(text)

    fun onSaveTag(text: String) = annotationManager.onSaveTag(text)

    // ── Definition ────────────────────────────────────────────────
    fun onShowDefinitionInput() = annotationManager.onShowDefinitionInput()

    fun onDismissDefinitionInput() = annotationManager.onDismissDefinitionInput()

    fun onDefinitionTextChanged(text: String) = annotationManager.onDefinitionTextChanged(text)

    fun onSaveDefinition(definition: String) = shareDictionaryManager.onSaveDefinition(definition)

    fun onAddToDictionary() = shareDictionaryManager.onAddToDictionary()

    // ── Share / Copy ──────────────────────────────────────────────
    fun onShareSelectedText(selectedText: String?) = shareDictionaryManager.onShareSelectedText(selectedText)

    fun onCopySelectedText() = shareDictionaryManager.onCopySelectedText()

    // ── Color picker / Panel ──────────────────────────────────────
    fun onShowColorPickerPopover() = annotationManager.onShowColorPickerPopover()

    fun onDismissColorPickerPopover() = annotationManager.onDismissColorPickerPopover()

    fun onToggleHighlightsPanel() = selectionManager.onToggleHighlightsPanel()

    // ── Bookmarks ─────────────────────────────────────────────────
    fun createBookmark(
        bookId: String,
        cfiLocation: String,
        titleOrSnippet: String,
        locatorJson: String? = null,
    ) = bookmarkManager.createBookmark(bookId, cfiLocation, titleOrSnippet, locatorJson)

    fun createBookmarkFromCurrentPosition(
        selectedBookId: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        chapters: List<BookChapter>,
        currentChapterIndex: Int,
        readiumLocator: Locator? = null,
    ) = bookmarkManager.createBookmarkFromCurrentPosition(
        selectedBookId,
        bookFormat,
        currentPdfPage,
        chapters,
        currentChapterIndex,
        readiumLocator,
    )

    // ── Dismiss / Debug ───────────────────────────────────────────
    fun onDismissContextMenu() = selectionManager.onDismissContextMenu()

    fun onDebugForceMenu() = selectionManager.onDebugForceMenu()

    fun onDebugForceColorPicker() = selectionManager.onDebugForceColorPicker()

    // ── Lifecycle bridge ──────────────────────────────────────────
    fun resetCoordinator() = selectionManager.resetCoordinator()

    fun onSelectionClearedFromLifecycle() = selectionManager.onSelectionClearedFromLifecycle()

    fun onCleared() {
        selectionManager.onCleared()
        highlightManager.onCleared()
        annotationManager.onCleared()
        bookmarkManager.onCleared()
        shareDictionaryManager.onCleared()
    }
}

package com.nexo.presentation.viewmodel.reader.interaction

import android.os.SystemClock
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.HIGHLIGHT_TAP_DEBOUNCE_MS
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.presentation.viewmodel.reader.SelectionCoordinator
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Note/Tag/Definition modals and onAnnotate orchestration (create Highlight -> promote ExistingHighlight -> open modal).
 * Tag suggestions order preserved: (DEFAULT_TAG_SUGGESTIONS + existingTags).distinct().filter{it != existingTag}
 */
internal class AnnotationManager(
    private val store: InteractionStateStore,
    private val selectionManager: SelectionManager,
    private val readerRepository: ReaderRepository,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    companion object {
        private val DEFAULT_TAG_SUGGESTIONS = listOf("cita", "pasaje", "idea", "ficción", "no-ficción", "favoritos")
    }

    fun onShowNoteModal() {
        val activeId = selectionManager.activeHighlightId() ?: return
        val existingText =
            store.value.highlights
                .find { it.id == activeId }
                ?.note ?: ""
        store.update { it.copy(showNoteModal = true, activeNoteText = existingText, showTagInput = false, showDefinitionInput = false) }
    }

    fun onDismissNoteModal() {
        store.update { it.copy(showNoteModal = false, activeNoteText = "") }
    }

    fun onSaveNote(text: String) {
        val activeId = selectionManager.activeHighlightId() ?: return
        val existing = store.value.highlights.find { it.id == activeId } ?: return
        val updated = existing.copy(note = text.ifBlank { null }, updatedAtEpochMillis = System.currentTimeMillis())
        scope.launch(mainDispatcher) { readerRepository.upsertHighlight(updated) }
        selectionManager.coordinator = SelectionCoordinator.MenuClosed()
        store.update {
            it.copy(
                selectionState = ReaderSelectionState.None,
                showNoteModal = false,
                activeNoteText = "",
                selectedText = null,
                selectionRect = null,
            )
        }
    }

    fun onAnnotate(
        selectedBookId: String?,
        bookFormat: String?,
        currentChapterIndex: Int,
        currentPdfPage: Int,
        chapters: List<BookChapter>,
    ) {
        val selection = store.value.selectionState
        when (selection) {
            is ReaderSelectionState.Existing -> onShowNoteModal()
            is ReaderSelectionState.New -> {
                val bookId = selectedBookId ?: return
                val text = selection.text
                val locatorJson = selection.locator?.let { CfiMigrator.locatorToJson(it) }
                val cfiRange =
                    if (bookFormat ==
                        "pdf"
                    ) {
                        "pdfpage:$currentPdfPage"
                    } else {
                        "readium:${selection.locator?.href ?: "epubcfi(/6/${currentChapterIndex + 1})"}"
                    }
                val newId = UUID.randomUUID().toString()
                val highlight =
                    Highlight(
                        id = newId,
                        bookId = bookId,
                        cfiRange = cfiRange,
                        textContent = text,
                        note = null,
                        color = HighlightColor.YELLOW.hex,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                        deletedAtEpochMillis = null,
                        locatorJson = locatorJson,
                    )
                scope.launch(mainDispatcher) { readerRepository.upsertHighlight(highlight) }
                selectionManager.coordinator =
                    SelectionCoordinator.ExistingHighlight(
                        highlight = highlight,
                        rect = selection.rect,
                        debounceUntil =
                            SystemClock.elapsedRealtime() + HIGHLIGHT_TAP_DEBOUNCE_MS,
                    )
                store.update {
                    it.copy(
                        selectionState = ReaderSelectionState.Existing(highlight, selection.rect),
                        selectedText = text,
                        selectionRect = selection.rect,
                    )
                }
                onShowNoteModal()
            }
            else -> {}
        }
    }

    fun onShowTagInput() {
        val activeId = selectionManager.activeHighlightId() ?: return
        val existingTag =
            store.value.highlights
                .find { it.id == activeId }
                ?.tag ?: ""
        val existingTags =
            store.value.highlights
                .mapNotNull { it.tag }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        val suggestions = (DEFAULT_TAG_SUGGESTIONS + existingTags).distinct().filter { it != existingTag }
        store.update {
            it.copy(
                showTagInput = true,
                activeTagText = existingTag,
                tagSuggestions = suggestions,
                showNoteModal = false,
                showDefinitionInput = false,
            )
        }
    }

    fun onDismissTagInput() {
        store.update { it.copy(showTagInput = false, activeTagText = "", tagSuggestions = emptyList()) }
    }

    fun onTagTextChanged(text: String) {
        store.update { it.copy(activeTagText = text) }
    }

    fun onSaveTag(text: String) {
        val activeId = selectionManager.activeHighlightId() ?: return
        val existing = store.value.highlights.find { it.id == activeId } ?: return
        val updated = existing.copy(tag = text.ifBlank { null }, updatedAtEpochMillis = System.currentTimeMillis())
        scope.launch(mainDispatcher) { readerRepository.upsertHighlight(updated) }
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onShowDefinitionInput() {
        store.update { it.copy(showDefinitionInput = true, activeDefinitionText = "", showNoteModal = false, showTagInput = false) }
    }

    fun onDismissDefinitionInput() {
        store.update { it.copy(showDefinitionInput = false, activeDefinitionText = "") }
    }

    fun onDefinitionTextChanged(text: String) {
        store.update { it.copy(activeDefinitionText = text) }
    }

    fun onShowColorPickerPopover() {
        store.update { it.copy(showColorPickerPopover = true) }
    }

    fun onDismissColorPickerPopover() {
        store.update { it.copy(showColorPickerPopover = false) }
    }

    override fun onCleared() {}
}

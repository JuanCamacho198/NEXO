package com.nexo.presentation.viewmodel.highlights

import com.nexo.domain.model.Highlight
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CrudStateHolder(
    private val readerRepository: ReaderRepository,
    private val scope: CoroutineScope,
    private val uiEvent: MutableSharedFlow<UiEvent>,
) {
    val highlightToEdit = MutableStateFlow<Highlight?>(null)
    val highlightToDelete = MutableStateFlow<Highlight?>(null)
    val highlightToChangeColor = MutableStateFlow<Highlight?>(null)
    val highlightToEditTag = MutableStateFlow<Highlight?>(null)
    val editNoteText = MutableStateFlow("")
    val editTagText = MutableStateFlow("")

    fun onEditHighlightNote(highlight: Highlight) {
        highlightToEdit.update { highlight }
        editNoteText.update { highlight.note ?: "" }
    }

    fun dismissEditHighlight() {
        highlightToEdit.update { null }
        editNoteText.update { "" }
    }

    fun onSaveHighlightNote(text: String) {
        val highlight = highlightToEdit.value ?: return
        val updated =
            highlight.copy(
                note = text.ifBlank { null },
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        scope.launch { readerRepository.upsertHighlight(updated) }
        highlightToEdit.update { null }
        editNoteText.update { "" }
    }

    fun onDeleteHighlight(highlight: Highlight) {
        highlightToDelete.update { highlight }
    }

    fun dismissDeleteHighlightDialog() {
        highlightToDelete.update { null }
    }

    fun confirmDeleteHighlight() {
        val highlight = highlightToDelete.value ?: return
        val updated =
            highlight.copy(
                deletedAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        scope.launch {
            readerRepository.upsertHighlight(updated)
            uiEvent.emit(UiEvent.ShowSnackbar("Highlight deleted"))
        }
        highlightToDelete.update { null }
    }

    fun onCopyHighlight(highlight: Highlight) {
        scope.launch { uiEvent.emit(UiEvent.CopyToClipboard(highlight.textContent)) }
    }

    fun onChangeHighlightColor(highlight: Highlight) {
        highlightToChangeColor.update { highlight }
    }

    fun dismissColorPicker() {
        highlightToChangeColor.update { null }
    }

    fun onConfirmColorChange(newColor: String) {
        val highlight = highlightToChangeColor.value ?: return
        val updated =
            highlight.copy(
                color = newColor,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        scope.launch {
            readerRepository.upsertHighlight(updated)
            uiEvent.emit(UiEvent.ShowSnackbar("Color changed"))
        }
        highlightToChangeColor.update { null }
    }

    fun onAddHighlightTag(highlight: Highlight) {
        highlightToEditTag.update { highlight }
        editTagText.update { highlight.tag ?: "" }
    }

    fun dismissTagEdit() {
        highlightToEditTag.update { null }
        editTagText.update { "" }
    }

    fun onTagEditTextChanged(text: String) {
        editTagText.update { text }
    }

    fun onSaveHighlightTag(tag: String) {
        val highlight = highlightToEditTag.value ?: return
        val updated =
            highlight.copy(
                tag = tag.ifBlank { null },
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        scope.launch {
            readerRepository.upsertHighlight(updated)
            uiEvent.emit(UiEvent.ShowSnackbar("Tag saved"))
        }
        highlightToEditTag.update { null }
        editTagText.update { "" }
    }

    fun onViewInBook(highlight: Highlight) {
        scope.launch { uiEvent.emit(UiEvent.OpenBookAtLocation(highlight.bookId, highlight.cfiRange)) }
    }
}

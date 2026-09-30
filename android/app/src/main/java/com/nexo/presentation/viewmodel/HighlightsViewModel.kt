package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.domain.model.Highlight
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.UiEvent
import com.nexo.presentation.feature.highlights.buildHighlightsUiState
import com.nexo.presentation.viewmodel.highlights.CrudStateHolder
import com.nexo.presentation.viewmodel.highlights.FilterStateHolder
import com.nexo.presentation.viewmodel.highlights.SyncStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.nexo.presentation.feature.highlights.HighlightsUiState as FeatureHighlightsUiState
import com.nexo.presentation.viewmodel.highlights.HighlightsSyncState as NewHighlightsSyncState

typealias HighlightsUiState = FeatureHighlightsUiState
typealias HighlightsSyncState = NewHighlightsSyncState

@HiltViewModel
class HighlightsViewModel
    @Inject
    constructor(
        private val readerRepository: ReaderRepository,
        private val homeRepository: HomeRepository,
        private val supabaseSync: com.nexo.data.remote.supabase.SupabaseProgressSync? = null,
    ) : ViewModel() {
        private val _uiEvent = MutableSharedFlow<UiEvent>()
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        val filterHolder = FilterStateHolder()
        val crudHolder = CrudStateHolder(readerRepository, viewModelScope, _uiEvent)
        val syncHolder = SyncStateHolder(supabaseSync, viewModelScope)

        val syncState: StateFlow<HighlightsSyncState> = syncHolder.syncState

        val uiState: StateFlow<HighlightsUiState> =
            combine(
                combine(
                    readerRepository.observeAllHighlights(),
                    readerRepository.observeAllBookmarks(),
                    homeRepository.observeBooks(),
                ) { highlights, bookmarks, books -> Triple(highlights, bookmarks, books) },
                combine(
                    filterHolder.typeFilter,
                    filterHolder.bookFilter,
                    filterHolder.colorFilter,
                ) { type, book, color -> Triple(type, book, color) },
                combine(
                    filterHolder.tagFilter,
                    filterHolder.searchQuery,
                    crudHolder.highlightToEdit,
                ) { tag, query, toEdit -> Triple(tag, query, toEdit) },
                combine(
                    crudHolder.highlightToDelete,
                    crudHolder.editNoteText,
                    crudHolder.highlightToChangeColor,
                ) { toDelete, noteText, toChangeColor -> Triple(toDelete, noteText, toChangeColor) },
                combine(
                    crudHolder.highlightToEditTag,
                    crudHolder.editTagText,
                ) { toEditTag, tagText -> toEditTag to tagText },
            ) { repoTriple, filterTriple1, filterTriple2, crudTriple1, crudPair ->
                val (highlights, bookmarks, books) = repoTriple
                val (type, book, color) = filterTriple1
                val (tag, query, highlightToEdit) = filterTriple2
                val (highlightToDelete, editNoteText, highlightToChangeColor) = crudTriple1
                val (highlightToEditTag, editTagText) = crudPair
                buildHighlightsUiState(
                    highlights = highlights,
                    bookmarks = bookmarks,
                    books = books,
                    type = type,
                    book = book,
                    color = color,
                    tag = tag,
                    query = query,
                    highlightToEdit = highlightToEdit,
                    highlightToDelete = highlightToDelete,
                    editNoteText = editNoteText,
                    highlightToChangeColor = highlightToChangeColor,
                    highlightToEditTag = highlightToEditTag,
                    editTagText = editTagText,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = HighlightsUiState(),
            )

        fun onTypeFilterChanged(filter: String) = filterHolder.onTypeFilterChanged(filter)

        fun onBookFilterChanged(bookId: String?) = filterHolder.onBookFilterChanged(bookId)

        fun onColorFilterChanged(color: String) = filterHolder.onColorFilterChanged(color)

        fun onColorFilterReset() = filterHolder.onColorFilterReset()

        fun onTagFilterChanged(tag: String?) = filterHolder.onTagFilterChanged(tag)

        fun onSearchQueryChanged(query: String) = filterHolder.onSearchQueryChanged(query)

        fun onEditHighlightNote(highlight: Highlight) = crudHolder.onEditHighlightNote(highlight)

        fun dismissEditHighlight() = crudHolder.dismissEditHighlight()

        fun onSaveHighlightNote(text: String) = crudHolder.onSaveHighlightNote(text)

        fun onDeleteHighlight(highlight: Highlight) = crudHolder.onDeleteHighlight(highlight)

        fun dismissDeleteHighlightDialog() = crudHolder.dismissDeleteHighlightDialog()

        fun confirmDeleteHighlight() = crudHolder.confirmDeleteHighlight()

        fun onCopyHighlight(highlight: Highlight) = crudHolder.onCopyHighlight(highlight)

        fun onChangeHighlightColor(highlight: Highlight) = crudHolder.onChangeHighlightColor(highlight)

        fun dismissColorPicker() = crudHolder.dismissColorPicker()

        fun onConfirmColorChange(newColor: String) = crudHolder.onConfirmColorChange(newColor)

        fun onAddHighlightTag(highlight: Highlight) = crudHolder.onAddHighlightTag(highlight)

        fun dismissTagEdit() = crudHolder.dismissTagEdit()

        fun onTagEditTextChanged(text: String) = crudHolder.onTagEditTextChanged(text)

        fun onSaveHighlightTag(tag: String) = crudHolder.onSaveHighlightTag(tag)

        fun onViewInBook(highlight: Highlight) = crudHolder.onViewInBook(highlight)

        fun syncHighlights(force: Boolean = false) = syncHolder.syncHighlights(force)

        // Keep applyFilters for tests compatibility, delegate to filterHolder
        internal fun applyFilters(
            highlights: List<Highlight>,
            type: String?,
            book: String?,
            color: Set<String>,
            tag: String?,
            query: String,
        ): List<Highlight> = filterHolder.applyFilters(highlights, type, book, color, tag, query)
    }

class HighlightsViewModelFactory(
    private val readerRepository: ReaderRepository,
    private val homeRepository: HomeRepository,
    private val supabaseSync: com.nexo.data.remote.supabase.SupabaseProgressSync? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HighlightsViewModel::class.java)) {
            return HighlightsViewModel(readerRepository, homeRepository, supabaseSync) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

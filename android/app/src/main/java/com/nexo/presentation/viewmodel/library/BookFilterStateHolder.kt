package com.nexo.presentation.viewmodel.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages all filter, sort, and search state for the library bookshelf.
 *
 * Handles debounced search input (300ms), filter toggles, view mode switching,
 * and the filter bottom sheet visibility.
 *
 * @param scope           CoroutineScope for debounce jobs (e.g. viewModelScope)
 * @param mainDispatcher  Dispatcher for UI-side work (default: [Dispatchers.Main])
 * @param onStateChanged  Callback invoked synchronously after every state mutation
 */
class BookFilterStateHolder(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onStateChanged: (BookFilterState) -> Unit = {},
) {
    private val _state = MutableStateFlow(BookFilterState())
    val state: StateFlow<BookFilterState> = _state.asStateFlow()

    private var searchJob: Job? = null

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    init {
        onStateChanged(_state.value)
    }

    /** Set the status filter (e.g. "all", "reading", "pending", "completed"). */
    fun onStatusFilterChanged(filter: String) {
        _state.update { it.copy(statusFilter = filter) }
        onStateChanged(_state.value)
    }

    /** Set the sort order (e.g. "date_added", "title", "author", "last_read"). */
    fun onSortByChanged(sort: String) {
        _state.update { it.copy(sortBy = sort) }
        onStateChanged(_state.value)
    }

    /** Toggle between grid and list view. */
    fun onToggleView() {
        _state.update { it.copy(isGridView = !it.isGridView) }
        onStateChanged(_state.value)
    }

    /** Toggle search bar visibility. Resets search state on close. */
    fun onToggleSearch() {
        _state.update {
            it.copy(
                showSearch = !it.showSearch,
                searchQuery = "",
                debouncedSearchQuery = "",
            )
        }
        onStateChanged(_state.value)
    }

    /**
     * Update the search query with 300ms debounce.
     * Cancels pending debounce on blank query and clears immediately.
     */
    fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }

        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(debouncedSearchQuery = "") }
            onStateChanged(_state.value)
            return
        }

        searchJob =
            scope.launch(mainDispatcher) {
                delay(SEARCH_DEBOUNCE_MS)
                _state.update { it.copy(debouncedSearchQuery = query) }
                onStateChanged(_state.value)
            }
    }

    /** Toggle the filter bottom sheet. */
    fun onToggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
        onStateChanged(_state.value)
    }

    /** Set the format filter and dismiss the filter sheet. */
    fun onFilterFormatChanged(format: String) {
        _state.update { it.copy(filterFormat = format, showFilterSheet = false) }
        onStateChanged(_state.value)
    }
}

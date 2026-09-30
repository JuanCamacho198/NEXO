package com.nexo.presentation.viewmodel.library

/**
 * UI-level filter, sort, and search state for the library bookshelf.
 * Managed by [BookFilterStateHolder].
 */
data class BookFilterState(
    val statusFilter: String = "all",
    val sortBy: String = "date_added",
    val isGridView: Boolean = true,
    val searchQuery: String = "",
    val debouncedSearchQuery: String = "",
    val showSearch: Boolean = false,
    val showFilterSheet: Boolean = false,
    val filterFormat: String = "all",
)

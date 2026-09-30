package com.nexo.presentation.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.LibraryUiState

@Preview(showBackground = true)
@Composable
private fun LibraryScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        val sampleBook = Book(id = "book-1", title = "Clean Code", author = "Robert C. Martin", coverPath = null, filePath = "/books/clean-code.epub", format = "epub", updatedAtEpochMillis = 1L)
        LibraryScreenContent(uiState = LibraryUiState(books = listOf(sampleBook), isLoading = false, isGridView = true, readingMinutesByBook = mapOf("book-1" to 40L), isSyncing = false, isRefreshing = false, syncError = null, downloadableBooks = emptyList(), downloadState = emptyMap(), statusFilter = "all", sortBy = "date_added", searchQuery = "", debouncedSearchQuery = "", showSearch = false, showFilterSheet = false, filterFormat = "all"), searchedBooks = listOf(sampleBook), firstDownloadError = null, contentPadding = PaddingValues(16.dp), driveAuthHelper = null, authSession = null, onOpenAccount = {}, onBookSelected = { _, _, _ -> }, onEditBook = {}, onRefresh = {}, onSearchToggle = {}, onSearchQueryChange = {}, onFilterToggle = {}, onStatusFilterChanged = {}, onSortByChanged = {}, onViewToggle = {}, onRequestDeleteBook = {}, onMarkCompleted = {}, onMarkPlanToRead = {}, onShare = {}, onDownload = {}, onDismissDownloadError = {}, onDismissDelete = {}, onConfirmDelete = {}, onConfirmLocalOnly = {}, onConfirmLocalAndDrive = {}, onFormatSelected = {}, onImportPdf = { _, _, _ -> }, onImportEpub = { _, _, _ -> })
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        val sampleBook = Book(id = "book-1", title = "Clean Code", author = "Robert C. Martin", coverPath = null, filePath = "/books/clean-code.epub", format = "epub", updatedAtEpochMillis = 1L)
        LibraryScreenContent(uiState = LibraryUiState(books = listOf(sampleBook), isLoading = false, isGridView = true, readingMinutesByBook = mapOf("book-1" to 40L), isSyncing = false, isRefreshing = false, syncError = null, downloadableBooks = emptyList(), downloadState = emptyMap(), statusFilter = "all", sortBy = "date_added", searchQuery = "", debouncedSearchQuery = "", showSearch = false, showFilterSheet = false, filterFormat = "all"), searchedBooks = listOf(sampleBook), firstDownloadError = null, contentPadding = PaddingValues(16.dp), driveAuthHelper = null, authSession = null, onOpenAccount = {}, onBookSelected = { _, _, _ -> }, onEditBook = {}, onRefresh = {}, onSearchToggle = {}, onSearchQueryChange = {}, onFilterToggle = {}, onStatusFilterChanged = {}, onSortByChanged = {}, onViewToggle = {}, onRequestDeleteBook = {}, onMarkCompleted = {}, onMarkPlanToRead = {}, onShare = {}, onDownload = {}, onDismissDownloadError = {}, onDismissDelete = {}, onConfirmDelete = {}, onConfirmLocalOnly = {}, onConfirmLocalAndDrive = {}, onFormatSelected = {}, onImportPdf = { _, _, _ -> }, onImportEpub = { _, _, _ -> })
    }
}

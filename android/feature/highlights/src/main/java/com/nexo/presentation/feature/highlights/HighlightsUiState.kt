package com.nexo.presentation.feature.highlights

import com.nexo.domain.model.Book
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor

data class HighlightsUiState(
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val books: List<Book> = emptyList(),
    val typeFilter: String = "all",
    val bookFilter: String? = null,
    val colorFilter: Set<String> = emptySet(),
    val tagFilter: String? = null,
    val searchQuery: String = "",
    val filteredHighlights: List<Highlight> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val availableHighlightColors: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val highlightToEdit: Highlight? = null,
    val highlightToDelete: Highlight? = null,
    val editNoteText: String = "",
    val colorCounts: Map<String, Int> = emptyMap(),
    val selectedHighlightForColorChange: Highlight? = null,
    val selectedHighlightForTagEdit: Highlight? = null,
    val editTagText: String = "",
) {
    val distinctTags: List<String> get() = availableTags
}

fun buildHighlightsUiState(
    highlights: List<Highlight>,
    bookmarks: List<Bookmark>,
    books: List<Book>,
    type: String,
    book: String?,
    color: Set<String>,
    tag: String?,
    query: String,
    highlightToEdit: Highlight?,
    highlightToDelete: Highlight?,
    editNoteText: String,
    highlightToChangeColor: Highlight?,
    highlightToEditTag: Highlight?,
    editTagText: String,
): HighlightsUiState {
    val availableTags =
        highlights
            .mapNotNull { it.tag }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    val colorCounts =
        highlights
            .filter { it.deletedAtEpochMillis == null }
            .groupBy { it.color }
            .mapValues { it.value.size }
    val availableHighlightColors =
        (HighlightColor.entries.map { it.hex } + highlights.map { it.color }.distinct())
            .distinct()
    return HighlightsUiState(
        highlights = highlights,
        bookmarks = bookmarks,
        books = books,
        typeFilter = type,
        bookFilter = book,
        colorFilter = color,
        tagFilter = tag,
        searchQuery = query,
        filteredHighlights = applyHighlightsFilters(highlights, type, book, color, tag, query),
        availableTags = availableTags,
        availableHighlightColors = availableHighlightColors,
        highlightToEdit = highlightToEdit,
        highlightToDelete = highlightToDelete,
        editNoteText = editNoteText,
        colorCounts = colorCounts,
        selectedHighlightForColorChange = highlightToChangeColor,
        selectedHighlightForTagEdit = highlightToEditTag,
        editTagText = editTagText,
        isLoading = false,
    )
}

fun applyHighlightsFilters(
    highlights: List<Highlight>,
    type: String?,
    book: String?,
    color: Set<String>,
    tag: String?,
    query: String,
): List<Highlight> =
    highlights.filter { highlight ->
        val matchesType =
            when (type) {
                null, "all" -> true
                "quotes" -> highlight.type == "quote"
                "ideas" -> highlight.type == "idea"
                "passages" -> highlight.type == "passage"
                else -> true
            }
        val matchesBook = book == null || highlight.bookId == book
        val matchesColor = color.isEmpty() || color.any { it.equals(highlight.color, ignoreCase = true) }
        val matchesTag = tag == null || highlight.tag.equals(tag, ignoreCase = true)
        val matchesSearch =
            query.isBlank() ||
                highlight.textContent.contains(query, ignoreCase = true) ||
                highlight.note?.contains(query, ignoreCase = true) == true
        matchesType && matchesBook && matchesColor && matchesTag && matchesSearch
    }

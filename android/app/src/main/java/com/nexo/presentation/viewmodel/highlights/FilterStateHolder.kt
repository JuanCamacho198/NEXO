package com.nexo.presentation.viewmodel.highlights

import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FilterStateHolder {
    val typeFilter = MutableStateFlow("all")
    val bookFilter = MutableStateFlow<String?>(null)
    val colorFilter = MutableStateFlow<Set<String>>(emptySet())
    val tagFilter = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("")

    fun onTypeFilterChanged(filter: String) {
        typeFilter.update { filter }
    }

    fun onBookFilterChanged(bookId: String?) {
        bookFilter.update { bookId }
    }

    fun onColorFilterChanged(color: String) {
        colorFilter.update { current ->
            if (color in current) current - color else current + color
        }
    }

    fun onColorFilterReset() {
        colorFilter.update { emptySet() }
    }

    fun onTagFilterChanged(tag: String?) {
        tagFilter.update { tag }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.update { query }
    }

    fun applyFilters(
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

    fun computeAvailableTags(highlights: List<Highlight>): List<String> =
        highlights
            .mapNotNull {
                it.tag
            }.filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun computeColorCounts(highlights: List<Highlight>): Map<String, Int> =
        highlights
            .filter {
                it.deletedAtEpochMillis == null
            }.groupBy { it.color }
            .mapValues { it.value.size }

    fun computeAvailableHighlightColors(highlights: List<Highlight>): List<String> =
        (
            HighlightColor.entries.map {
                it.hex
            } + highlights.map { it.color }.distinct()
        ).distinct()
}

package com.nexo.presentation.feature.highlights

import com.nexo.domain.model.Book
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor

val sampleBook: Book =
    Book(
        id = "book-1",
        title = "Deep Work",
        author = "Cal Newport",
        coverPath = null,
        filePath = "/books/deep-work.epub",
        format = "epub",
        updatedAtEpochMillis = 1L,
    )

val sampleHighlight: Highlight =
    Highlight(
        id = "hl-1",
        bookId = "book-1",
        cfiRange = "epubcfi(/6/14)",
        textContent = "Deep work is the ability to focus without distraction on a cognitively demanding task.",
        note = "Core definition.",
        color = HighlightColor.YELLOW.hex,
        updatedAtEpochMillis = 1L,
        deletedAtEpochMillis = null,
        tag = "focus",
        type = "quote",
    )

val sampleHighlightsUiState: HighlightsUiState =
    HighlightsUiState(
        highlights = listOf(sampleHighlight),
        bookmarks =
            listOf(
                Bookmark(
                    id = "bm-1",
                    bookId = "book-1",
                    cfiLocation = "epubcfi(/6/20)",
                    titleOrSnippet = "Deep work",
                    updatedAtEpochMillis = 1L,
                    deletedAtEpochMillis = null,
                ),
            ),
        books = listOf(sampleBook),
        typeFilter = "all",
        bookFilter = null,
        colorFilter = emptySet(),
        tagFilter = null,
        searchQuery = "",
        filteredHighlights = listOf(sampleHighlight),
        availableTags = listOf("focus"),
        isLoading = false,
        errorMessage = null,
        highlightToEdit = null,
        highlightToDelete = null,
        editNoteText = "",
        colorCounts = mapOf(HighlightColor.YELLOW.hex to 1),
        selectedHighlightForColorChange = null,
        selectedHighlightForTagEdit = null,
        editTagText = "",
    )

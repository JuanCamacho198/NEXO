package com.nexo.domain.repository

import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ReaderRepository {
    fun observeProgress(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgress?>

    suspend fun upsertProgress(
        progress: ReadingProgress,
        userId: String,
    )

    suspend fun getProgressForBook(
        bookId: String,
        userId: String,
    ): ReadingProgress?

    /**
     * Marks a book as being read and updates its stored progress percentage.
     * The Home "Continue reading" section filters on `reading_state == "reading"`,
     * so a book only appears there once this is called.
     */
    suspend fun updateBookReadingState(
        bookId: String,
        userId: String,
        progressPercent: Float,
        updatedAt: Long,
    )

    fun observeAllHighlights(userId: String): Flow<List<Highlight>>

    fun observeHighlights(
        bookId: String,
        userId: String,
    ): Flow<List<Highlight>>

    suspend fun upsertHighlight(
        highlight: Highlight,
        userId: String,
    )

    suspend fun getHighlightsForBook(
        bookId: String,
        userId: String,
    ): List<Highlight>

    fun observeAllTags(userId: String): Flow<List<String>> = flowOf(emptyList())

    fun observeAllBookmarks(userId: String): Flow<List<Bookmark>>

    fun observeBookmarks(
        bookId: String,
        userId: String,
    ): Flow<List<Bookmark>>

    suspend fun upsertBookmark(
        bookmark: Bookmark,
        userId: String,
    )

    suspend fun getBookmarksForBook(
        bookId: String,
        userId: String,
    ): List<Bookmark>
}

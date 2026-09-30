package com.nexo.domain.repository

import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ReaderRepository {
    fun observeProgress(bookId: String): Flow<ReadingProgress?>

    suspend fun upsertProgress(progress: ReadingProgress)

    suspend fun getProgressForBook(bookId: String): ReadingProgress?

    /**
     * Marks a book as being read and updates its stored progress percentage.
     * The Home "Continue reading" section filters on `reading_state == "reading"`,
     * so a book only appears there once this is called.
     */
    suspend fun updateBookReadingState(
        bookId: String,
        progressPercent: Float,
        updatedAt: Long,
    )

    fun observeAllHighlights(): Flow<List<Highlight>>

    fun observeHighlights(bookId: String): Flow<List<Highlight>>

    suspend fun upsertHighlight(highlight: Highlight)

    suspend fun getHighlightsForBook(bookId: String): List<Highlight>

    fun observeAllTags(): Flow<List<String>> = flowOf(emptyList())

    fun observeAllBookmarks(): Flow<List<Bookmark>>

    fun observeBookmarks(bookId: String): Flow<List<Bookmark>>

    suspend fun upsertBookmark(bookmark: Bookmark)

    suspend fun getBookmarksForBook(bookId: String): List<Bookmark>
}

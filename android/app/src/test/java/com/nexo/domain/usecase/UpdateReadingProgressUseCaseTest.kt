package com.nexo.domain.usecase

import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateReadingProgressUseCaseTest {
    @Test
    fun invoke_writesProgressIntoRepository() =
        runTest {
            val repository = FakeReaderRepository()
            val useCase = UpdateReadingProgressUseCase(repository)

            useCase(bookId = "book-1", cfiLocation = "epubcfi(/6/2!/4/1:0)", percentage = 42f)

            val stored = repository.lastUpserted
            assertNotNull(stored)
            assertEquals("progress-book-1", stored?.id)
            assertEquals("book-1", stored?.bookId)
            assertEquals("epubcfi(/6/2!/4/1:0)", stored?.cfiLocation)
            assertEquals(42f, stored?.percentage)
            assertTrue((stored?.updatedAtEpochMillis ?: 0L) > 0L)
        }

    private class FakeReaderRepository : ReaderRepository {
        var lastUpserted: ReadingProgress? = null

        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: ReadingProgress) {
            lastUpserted = progress
        }

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) {
            // No-op in fake
        }

        override fun observeAllHighlights(): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: Highlight) = Unit

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getProgressForBook(bookId: String): ReadingProgress? = null

        override suspend fun getHighlightsForBook(bookId: String): List<Highlight> = emptyList()

        override suspend fun getBookmarksForBook(bookId: String): List<Bookmark> = emptyList()
    }
}

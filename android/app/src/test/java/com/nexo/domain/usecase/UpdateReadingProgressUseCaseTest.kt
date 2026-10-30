package com.nexo.domain.usecase

import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.testutil.TEST_USER_ID
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

            useCase(bookId = "book-1", userId = TEST_USER_ID, cfiLocation = "epubcfi(/6/2!/4/1:0)", percentage = 42f)

            val stored = repository.lastUpserted
            assertNotNull(stored)
            assertEquals("progress-book-1", stored?.id)
            assertEquals("book-1", stored?.bookId)
            assertEquals("epubcfi(/6/2!/4/1:0)", stored?.cfiLocation)
            assertEquals(42f, stored?.percentage)
            assertTrue((stored?.updatedAtEpochMillis ?: 0L) > 0L)
            assertEquals(TEST_USER_ID, repository.lastUserId)
        }

    private class FakeReaderRepository : ReaderRepository {
        var lastUpserted: ReadingProgress? = null
        var lastUserId: String? = null

        override fun observeProgress(
            bookId: String,
            userId: String,
        ): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(
            progress: ReadingProgress,
            userId: String,
        ) {
            lastUpserted = progress
            lastUserId = userId
        }

        override suspend fun updateBookReadingState(
            bookId: String,
            userId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) {
            // No-op in fake
        }

        override fun observeAllHighlights(userId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(
            bookId: String,
            userId: String,
        ): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(
            highlight: Highlight,
            userId: String,
        ) = Unit

        override fun observeAllBookmarks(userId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(
            bookId: String,
            userId: String,
        ): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(
            bookmark: Bookmark,
            userId: String,
        ) = Unit

        override suspend fun getProgressForBook(
            bookId: String,
            userId: String,
        ): ReadingProgress? = null

        override suspend fun getHighlightsForBook(
            bookId: String,
            userId: String,
        ): List<Highlight> = emptyList()

        override suspend fun getBookmarksForBook(
            bookId: String,
            userId: String,
        ): List<Bookmark> = emptyList()
    }
}

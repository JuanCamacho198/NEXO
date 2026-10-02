package com.nexo.domain.usecase

import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WU2b: `books.progress_percentage` was dropped, so the use case is canonical-only
 * (`reading_progress.percentage`) with no cache fallback to merge.
 */
class GetBookProgressUseCaseTest {
    @Test
    fun observeProgressPercent_returnsCanonicalPercentage() =
        runBlocking {
            val bookId = "book-1"
            val useCase =
                GetBookProgressUseCase(
                    FakeReaderRepo(
                        ReadingProgress(
                            id = "progress-$bookId",
                            bookId = bookId,
                            cfiLocation = "epubcfi",
                            percentage = 60f,
                            updatedAtEpochMillis = 5000L,
                        ),
                    ),
                )

            val pct = useCase.observeProgressPercent(bookId).first()
            assertEquals(60f, pct, 0.001f)
        }

    @Test
    fun observeProgressPercent_returnsZero_whenCanonicalMissing() =
        runBlocking {
            val useCase = GetBookProgressUseCase(FakeReaderRepo(null))

            val pct = useCase.observeProgressPercent("book-2").first()
            assertEquals(0f, pct, 0.001f)
        }

    @Test
    fun homeAndLibrary_parity_sameCanonical() =
        runBlocking {
            // Simulate Home and Library both observing the same book via the shared use case.
            val bookId = "book-parity"
            val useCase =
                GetBookProgressUseCase(
                    FakeReaderRepo(
                        ReadingProgress(
                            id = "progress-$bookId",
                            bookId = bookId,
                            cfiLocation = "epubcfi",
                            percentage = 77f,
                            updatedAtEpochMillis = 9000L,
                        ),
                    ),
                )

            val homePct = useCase.observeProgressPercent(bookId).first()
            val libraryPct = useCase.observeProgressPercent(bookId).first()

            assertEquals(homePct, libraryPct, 0.001f)
            assertEquals(77f, homePct, 0.001f)
        }

    private class FakeReaderRepo(
        private val progress: ReadingProgress?,
    ) : ReaderRepository {
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(progress)

        override suspend fun upsertProgress(progress: ReadingProgress) {}

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) {}

        override suspend fun getProgressForBook(bookId: String): ReadingProgress? = progress

        override fun observeAllHighlights(): Flow<List<com.nexo.domain.model.Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<com.nexo.domain.model.Highlight>> = MutableStateFlow(emptyList())

        override fun observeAllTags(): Flow<List<String>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: com.nexo.domain.model.Highlight) {}

        override suspend fun getHighlightsForBook(bookId: String): List<com.nexo.domain.model.Highlight> = emptyList()

        override fun observeAllBookmarks(): Flow<List<com.nexo.domain.model.Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<com.nexo.domain.model.Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: com.nexo.domain.model.Bookmark) {}

        override suspend fun getBookmarksForBook(bookId: String): List<com.nexo.domain.model.Bookmark> = emptyList()
    }
}

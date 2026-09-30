package com.nexo.domain.usecase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GetBookProgressUseCaseTest {
    @Test
    fun observeProgressPercent_canonicalWins_overCache() =
        runBlocking {
            val bookId = "book-1"
            val readingProgressDao = FakeReadingProgressDao()
            readingProgressDao.seed(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi",
                    percentage = 60f,
                    updatedAtEpochMillis = 5000L,
                ),
            )
            val bookDao = FakeBookDao()
            bookDao.seed(
                BookEntity(
                    id = bookId,
                    title = "T",
                    author = null,
                    coverPath = null,
                    filePath = "/f.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 20f,
                    progressUpdatedAtEpochMillis = 1000L,
                ),
            )
            val useCase =
                GetBookProgressUseCase(readerRepository = FakeReaderRepo(), readingProgressDao = readingProgressDao, bookDao = bookDao)

            val pct = useCase.observeProgressPercent(bookId).first()
            assertEquals(60f, pct, 0.001f)
        }

    @Test
    fun observeProgressPercent_fallbackToCache_whenCanonicalNull() =
        runBlocking {
            val bookId = "book-2"
            val readingProgressDao = FakeReadingProgressDao() // empty
            val bookDao = FakeBookDao()
            bookDao.seed(
                BookEntity(
                    id = bookId,
                    title = "T",
                    author = null,
                    coverPath = null,
                    filePath = "/f.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 35f,
                    progressUpdatedAtEpochMillis = 1000L,
                ),
            )
            val useCase =
                GetBookProgressUseCase(readerRepository = FakeReaderRepo(), readingProgressDao = readingProgressDao, bookDao = bookDao)

            val pct = useCase.observeProgressPercent(bookId).first()
            assertEquals(35f, pct, 0.001f)
        }

    @Test
    fun observeProgressPercent_returnsZero_whenBothNull() =
        runBlocking {
            val bookId = "book-3"
            val useCase =
                GetBookProgressUseCase(
                    readerRepository = FakeReaderRepo(),
                    readingProgressDao = FakeReadingProgressDao(),
                    bookDao = FakeBookDao(),
                )

            val pct = useCase.observeProgressPercent(bookId).first()
            assertEquals(0f, pct, 0.001f)
        }

    @Test
    fun homeAndLibrary_parity_sameCanonical() =
        runBlocking {
            // Simulate Home and Library both observing same book via shared use case
            val bookId = "book-parity"
            val readingProgressDao = FakeReadingProgressDao()
            readingProgressDao.seed(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi",
                    percentage = 77f,
                    updatedAtEpochMillis = 9000L,
                ),
            )
            val bookDao = FakeBookDao()
            bookDao.seed(
                BookEntity(
                    id = bookId,
                    title = "T",
                    author = null,
                    coverPath = null,
                    filePath = "/f.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 10f,
                    progressUpdatedAtEpochMillis = 1000L,
                ),
            )
            val useCase =
                GetBookProgressUseCase(readerRepository = FakeReaderRepo(), readingProgressDao = readingProgressDao, bookDao = bookDao)

            val homePct = useCase.observeProgressPercent(bookId).first()
            val libraryPct = useCase.observeProgressPercent(bookId).first()

            assertEquals(homePct, libraryPct, 0.001f)
            assertEquals(77f, homePct, 0.001f)
        }

    private class FakeReaderRepo : ReaderRepository {
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: ReadingProgress) {}

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) {}

        override suspend fun getProgressForBook(bookId: String): ReadingProgress? = null

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

    private class FakeReadingProgressDao : ReadingProgressDao {
        private val map = mutableMapOf<String, ReadingProgressEntity>()

        fun seed(e: ReadingProgressEntity) {
            map[e.bookId] = e
        }

        override fun observeProgressForBook(bookId: String): Flow<ReadingProgressEntity?> = MutableStateFlow(map[bookId])

        override suspend fun getProgressForBook(bookId: String): ReadingProgressEntity? = map[bookId]

        override suspend fun upsert(progress: ReadingProgressEntity) {
            map[progress.bookId] = progress
        }

        override suspend fun getAll(): List<ReadingProgressEntity> = map.values.toList()

        override fun observeAll(): Flow<List<ReadingProgressEntity>> = MutableStateFlow(map.values.toList())

        override suspend fun count(): Int = map.size
    }

    private class FakeBookDao : BookDao {
        private val map = mutableMapOf<String, BookEntity>()

        fun seed(e: BookEntity) {
            map[e.id] = e
        }

        override fun observeAllBooks(): Flow<List<BookEntity>> = MutableStateFlow(map.values.toList())

        override fun observeReadingBooks(): Flow<List<BookEntity>> = MutableStateFlow(map.values.toList())

        override fun observeAllBooksPaged(): androidx.paging.PagingSource<Int, BookEntity> = com.nexo.testutil.FakePagingSource(emptyList())

        override suspend fun upsert(book: BookEntity) {
            map[book.id] = book
        }

        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(map[bookId])

        override suspend fun getBookById(bookId: String): BookEntity? = map[bookId]

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {}

        override suspend fun deleteById(bookId: String) {
            map.remove(bookId)
        }

        override suspend fun updateRating(
            bookId: String,
            rating: Int?,
        ) {}

        override suspend fun updateStatus(
            bookId: String,
            status: String?,
            updatedAt: Long,
        ) {}

        override suspend fun startReading(
            bookId: String,
            updatedAt: Long,
        ) {}

        override suspend fun updateReadingProgress(
            bookId: String,
            progress: Float,
            updatedAt: Long,
        ) {
            map[bookId]?.let {
                map[bookId] =
                    it.copy(progressPercentage = progress, progressUpdatedAtEpochMillis = updatedAt)
            }
        }

        override suspend fun completeReading(
            bookId: String,
            updatedAt: Long,
        ) {}

        override suspend fun updateMetadata(
            bookId: String,
            title: String,
            author: String?,
            description: String?,
            coverPath: String?,
            genre: String?,
            language: String?,
            publisher: String?,
            tags: String?,
            publishedDate: String?,
            updatedAt: Long,
        ) {
        }

        override suspend fun count(): Int = map.size
    }
}

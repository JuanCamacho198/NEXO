package com.nexo.data.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressReconcilerTest {
    @Test
    fun reconcile_canonicalNewer_wins() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            val bookId = "book-1"
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Book",
                    author = null,
                    coverPath = null,
                    filePath = "/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 10f,
                    progressUpdatedAtEpochMillis = 1000L,
                ),
            )
            progressDao.upsert(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = 50f,
                    updatedAtEpochMillis = 5000L,
                ),
            )

            reconciler.reconcile(bookId)

            // canonical newer (5000 > 1000) => book cache should be updated to 50
            val updatedBook = bookDao.getBookById(bookId)
            assertEquals(50f, updatedBook?.progressPercentage)
            assertEquals(5000L, updatedBook?.progressUpdatedAtEpochMillis)
        }

    @Test
    fun reconcile_cacheNewer_wins_pushToCanonical() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            val bookId = "book-2"
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Book",
                    author = null,
                    coverPath = null,
                    filePath = "/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 2000L,
                    progressPercentage = 80f,
                    progressUpdatedAtEpochMillis = 8000L,
                ),
            )
            progressDao.upsert(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = 20f,
                    updatedAtEpochMillis = 3000L,
                ),
            )

            reconciler.reconcile(bookId)

            // cache newer (8000 > 3000) => canonical should be updated to 80
            val updatedProgress = progressDao.getProgressForBook(bookId)
            assertEquals(80f, updatedProgress?.percentage)
            assertEquals(8000L, updatedProgress?.updatedAtEpochMillis)
        }

    @Test
    fun reconcile_equalTimestamp_canonicalWins() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            val bookId = "book-3"
            val ts = 5000L
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Book",
                    author = null,
                    coverPath = null,
                    filePath = "/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 30f,
                    progressUpdatedAtEpochMillis = ts,
                ),
            )
            progressDao.upsert(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = 70f,
                    updatedAtEpochMillis = ts,
                ),
            )

            reconciler.reconcile(bookId)

            // equal => canonical wins (progAt >= bookAt) => book updated to 70
            val updatedBook = bookDao.getBookById(bookId)
            assertEquals(70f, updatedBook?.progressPercentage)
        }

    @Test
    fun reconcile_noCanonical_noOpOrSeed() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            val bookId = "book-4"
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Book",
                    author = null,
                    coverPath = null,
                    filePath = "/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 25f,
                    progressUpdatedAtEpochMillis = 2000L,
                ),
            )
            // No progress row

            reconciler.reconcile(bookId)

            // Should not crash, and no progress created (seed is logged but not inserted in this impl)
            // The current reconciler returns early without inserting; verify no exception and progress still null
            val progress = progressDao.getProgressForBook(bookId)
            assertEquals(null, progress)
        }

    @Test
    fun reconcile_samePercentage_noOp() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            val bookId = "book-5"
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Book",
                    author = null,
                    coverPath = null,
                    filePath = "/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                    progressPercentage = 42f,
                    progressUpdatedAtEpochMillis = 5000L,
                ),
            )
            progressDao.upsert(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = 42f,
                    updatedAtEpochMillis = 6000L,
                ),
            )

            reconciler.reconcile(bookId)

            // same pct => early return, no update
            val book = bookDao.getBookById(bookId)
            assertEquals(42f, book?.progressPercentage)
            // should stay at original bookAt since no update
            assertEquals(5000L, book?.progressUpdatedAtEpochMillis)
        }

    // ── Fakes ──

    private class FakeBookDao : BookDao {
        private val booksMap = mutableMapOf<String, BookEntity>()
        private val flowState = MutableStateFlow<List<BookEntity>>(emptyList())

        override fun observeAllBooks(): Flow<List<BookEntity>> = flowState.map { it.filter { b -> b.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> = flowState

        override fun observeAllBooksPaged(): androidx.paging.PagingSource<Int, BookEntity> = com.nexo.testutil.FakePagingSource(emptyList())

        override suspend fun upsert(book: BookEntity) {
            booksMap[book.id] = book
            flowState.value = booksMap.values.toList()
        }

        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(booksMap[bookId])

        override suspend fun getBookById(bookId: String): BookEntity? = booksMap[bookId]

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {
            booksMap[bookId]?.let {
                booksMap[bookId] =
                    it.copy(deletedAtEpochMillis = deletedAt)
            }
        }

        override suspend fun deleteById(bookId: String) {
            booksMap.remove(bookId)
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
            booksMap[bookId]?.let {
                booksMap[bookId] =
                    it.copy(progressPercentage = progress, progressUpdatedAtEpochMillis = updatedAt, updatedAtEpochMillis = updatedAt)
            }
            flowState.value = booksMap.values.toList()
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

        override suspend fun count(): Int = booksMap.size
    }

    private class FakeReadingProgressDao : ReadingProgressDao {
        private val map = mutableMapOf<String, ReadingProgressEntity>()

        override fun observeProgressForBook(bookId: String): Flow<ReadingProgressEntity?> = MutableStateFlow(map[bookId])

        override suspend fun getProgressForBook(bookId: String): ReadingProgressEntity? = map[bookId]

        override suspend fun upsert(progress: ReadingProgressEntity) {
            map[progress.bookId] = progress
        }

        override suspend fun getAll(): List<ReadingProgressEntity> = map.values.toList()

        override fun observeAll(): Flow<List<ReadingProgressEntity>> = MutableStateFlow(map.values.toList())

        override suspend fun count(): Int = map.size
    }
}

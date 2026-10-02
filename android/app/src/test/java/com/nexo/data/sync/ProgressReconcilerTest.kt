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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

            // WU2a: canonical wins, but the cache column is write-dead — it stays untouched.
            val updatedBook = bookDao.getBookById(bookId)
            assertEquals(10f, updatedBook?.progressPercentage)
            assertEquals(1000L, updatedBook?.progressUpdatedAtEpochMillis)
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

            // WU2a: equal => canonical wins, cache write retired => cache stays 30
            val updatedBook = bookDao.getBookById(bookId)
            assertEquals(30f, updatedBook?.progressPercentage)
        }

    @Test
    fun reconcile_cacheOnly_backfillsCanonical() =
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

            // WU2a: a cache-only position is backfilled into canonical (FR-10).
            val progress = progressDao.getProgressForBook(bookId)
            assertEquals(25f, progress?.percentage)
            assertEquals(2000L, progress?.updatedAtEpochMillis)
        }

    /**
     * FR-10 / G2 acceptance: given divergent, cache-only and canonical-only rows,
     * the backfill leaves no reading position representable only in the retired
     * books.progress_percentage column.
     */
    @Test
    fun backfill_divergent_cacheOnly_canonicalOnly_preservesEveryPosition() =
        runBlocking {
            val bookDao = FakeBookDao()
            val progressDao = FakeReadingProgressDao()
            val reconciler = ProgressReconciler(bookDao, progressDao)

            // A: divergent, canonical newer (canonical wins; stale cache 10 is obsolete).
            seed(bookDao, progressDao, "A", cachePct = 10f, cacheAt = 1000L, canonPct = 50f, canonAt = 5000L)
            // B: divergent, cache newer (offline read — must be pushed to canonical).
            seed(bookDao, progressDao, "B", cachePct = 80f, cacheAt = 8000L, canonPct = 20f, canonAt = 3000L)
            // C: cache-only (no canonical row — must be seeded).
            seed(bookDao, progressDao, "C", cachePct = 33f, cacheAt = 2000L, canonPct = null, canonAt = null)
            // D: canonical-only (cache 0 — untouched).
            seed(bookDao, progressDao, "D", cachePct = 0f, cacheAt = null, canonPct = 66f, canonAt = 6000L)
            // E: both stores agree (untouched).
            seed(bookDao, progressDao, "E", cachePct = 44f, cacheAt = 4000L, canonPct = 44f, canonAt = 4000L)

            reconciler.reconcileAll()

            // Canonical now represents the reconciled winner for every book.
            assertEquals(50f, progressDao.getProgressForBook("A")?.percentage)
            assertEquals(80f, progressDao.getProgressForBook("B")?.percentage)
            assertEquals(33f, progressDao.getProgressForBook("C")?.percentage)
            assertEquals(66f, progressDao.getProgressForBook("D")?.percentage)
            assertEquals(44f, progressDao.getProgressForBook("E")?.percentage)

            // G2 invariant: zero positions live ONLY in the retired column.
            // 1) no cache>0 without a canonical row; 2) no cache strictly newer than canonical.
            for (book in listOf("A", "B", "C", "D", "E")) {
                val entity = bookDao.getBookById(book)!!
                val canonical = progressDao.getProgressForBook(book)
                if (entity.progressPercentage > 0f) {
                    assertNotNull("cache-only position left unbackfilled: $book", canonical)
                }
                if (canonical != null) {
                    val cacheAt = entity.progressUpdatedAtEpochMillis ?: 0L
                    assertTrue(
                        "cache newer than canonical left unbackfilled: $book",
                        cacheAt <= canonical.updatedAtEpochMillis,
                    )
                }
            }
        }

    private suspend fun seed(
        bookDao: FakeBookDao,
        progressDao: FakeReadingProgressDao,
        bookId: String,
        cachePct: Float,
        cacheAt: Long?,
        canonPct: Float?,
        canonAt: Long?,
    ) {
        bookDao.upsert(
            BookEntity(
                id = bookId,
                title = bookId,
                author = null,
                coverPath = null,
                filePath = "/$bookId.epub",
                format = "epub",
                updatedAtEpochMillis = 1000L,
                progressPercentage = cachePct,
                progressUpdatedAtEpochMillis = cacheAt,
            ),
        )
        if (canonPct != null && canonAt != null) {
            progressDao.upsert(
                ReadingProgressEntity(
                    id = "progress-$bookId",
                    bookId = bookId,
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = canonPct,
                    updatedAtEpochMillis = canonAt,
                ),
            )
        }
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

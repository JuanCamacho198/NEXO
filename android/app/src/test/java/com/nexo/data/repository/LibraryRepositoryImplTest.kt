package com.nexo.data.repository

import androidx.paging.PagingSource
import com.nexo.data.epub.EpubMetadata
import com.nexo.data.epub.EpubParserService
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.pdf.PdfMetadata
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.storage.CoverStorage
import com.nexo.testutil.FakePagingSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class LibraryRepositoryImplTest {
    @Test
    fun importBookFromEpub_persistsMetadataIntoBookDao() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService =
                        FakeEpubParserService(
                            Result.success(
                                EpubMetadata(
                                    title = "Domain Driven Design",
                                    author = "Eric Evans",
                                    coverImageBytes = null,
                                ),
                            ),
                        ),
                    pdfParserService = FakePdfParserService(Result.success(PdfMetadata("PDF Book", null, 10, 100))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            val result =
                repository.importBookFromEpub(
                    request =
                        com.nexo.domain.model.BookImportRequest(
                            sourcePath = "content://books/ddd.epub",
                            fallbackTitle = "ddd.epub",
                        ),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            val inserted = fakeDao.lastUpserted
            assertNotNull(inserted)
            assertEquals("Domain Driven Design", inserted?.title)
            assertEquals("Eric Evans", inserted?.author)
            assertEquals("content://books/ddd.epub", inserted?.filePath)
            assertEquals("epub", inserted?.format)
            assertEquals(null, inserted?.coverPath)
        }

    @Test
    fun importBookFromPdf_persistsMetadataIntoBookDao() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("Should not be called"))),
                    pdfParserService =
                        FakePdfParserService(
                            Result.success(
                                PdfMetadata(
                                    title = "PDF Guide",
                                    author = "John Doe",
                                    pageCount = 250,
                                    fileSizeBytes = 2048000L,
                                ),
                            ),
                        ),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            val result =
                repository.importBookFromPdf(
                    request =
                        com.nexo.domain.model.BookImportRequest(
                            sourcePath = "content://books/guide.pdf",
                            fallbackTitle = "guide.pdf",
                        ),
                    file = java.io.File("guide.pdf"),
                )

            assertTrue(result.isSuccess)
            val inserted = fakeDao.lastUpserted
            assertNotNull(inserted)
            assertEquals("PDF Guide", inserted?.title)
            assertEquals("John Doe", inserted?.author)
            assertEquals("content://books/guide.pdf", inserted?.filePath)
            assertEquals("pdf", inserted?.format)
            assertEquals(null, inserted?.coverPath)
        }

    @Test
    fun importBookFromPdf_succeedsEvenWhenCoverSaveFails() =
        runBlocking {
            // REQ-07: cover save failure is mapped to COVER_FAILED and never blocks import.
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("Should not be called"))),
                    pdfParserService =
                        FakePdfParserService(
                            Result.success(
                                PdfMetadata(
                                    title = "PDF With Cover",
                                    author = "Cover Author",
                                    pageCount = 120,
                                    fileSizeBytes = 1024L,
                                    coverBytes = byteArrayOf(1, 2, 3),
                                ),
                            ),
                        ),
                    coverStorage = FailingCoverStorage(),
                    outboxDao = mockk(),
                )

            val result =
                repository.importBookFromPdf(
                    request =
                        com.nexo.domain.model.BookImportRequest(
                            sourcePath = "content://books/cover.pdf",
                            fallbackTitle = "cover.pdf",
                        ),
                    file = java.io.File("cover.pdf"),
                )

            assertTrue(result.isSuccess)
            val inserted = fakeDao.lastUpserted
            assertNotNull(inserted)
            assertEquals("PDF With Cover", inserted?.title)
            assertEquals(null, inserted?.coverPath)
        }

    @Test
    fun importBookFromPdf_returnsFailureWhenMetadataExtractionFails() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("Should not be called"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("Invalid PDF"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            val result =
                repository.importBookFromPdf(
                    request =
                        com.nexo.domain.model.BookImportRequest(
                            sourcePath = "content://books/broken.pdf",
                            fallbackTitle = "broken.pdf",
                        ),
                    file = java.io.File("broken.pdf"),
                )

            assertTrue(result.isFailure)
        }

    @Test
    fun updateBookMetadata_persistsAllFieldsAndQueuesOutboxUpdate() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("unused"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("unused"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = outboxDao,
                )

            fakeDao.upsert(
                BookEntity(
                    id = "meta-book",
                    title = "Original",
                    author = "Original Author",
                    coverPath = null,
                    filePath = "/meta.epub",
                    format = "epub",
                    updatedAtEpochMillis = 10L,
                ),
            )

            val result =
                repository.updateBookMetadata(
                    bookId = "meta-book",
                    title = "Edited",
                    author = "New Author",
                    description = "New description",
                    coverPath = null,
                    genre = "Fiction",
                    language = "en",
                    publisher = "Acme Press",
                    tags = "tag1, tag2",
                    publishedDate = "2020-01-01",
                )

            assertTrue(result.isSuccess)

            // All 10 fields land in the DAO row, including the 5 new metadata fields.
            val updated = fakeDao.getBookById("meta-book")
            assertEquals("Edited", updated?.title)
            assertEquals("New Author", updated?.author)
            assertEquals("New description", updated?.description)
            assertEquals("Fiction", updated?.genre)
            assertEquals("en", updated?.language)
            assertEquals("Acme Press", updated?.publisher)
            assertEquals("tag1, tag2", updated?.tags)
            assertEquals("2020-01-01", updated?.publishedDate)

            // Bug 1 fix: a metadata edit queues a BOOK UPDATE outbox entry.
            coVerify {
                outboxDao.insert(
                    match {
                        it.entityType == SyncEntityType.BOOK.name &&
                            it.entityId == "meta-book" &&
                            it.operation == SyncOperation.UPDATE.name
                    },
                )
            }
        }

    @Test
    fun observeTotalReadingTime_mapsNullToZero() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val fakeReadingStatsDao = FakeReadingStatsDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = fakeReadingStatsDao,
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("unused"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("unused"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            fakeReadingStatsDao.totalMinutesState.value = null
            assertEquals(0L, repository.observeTotalReadingTime().first())

            fakeReadingStatsDao.totalMinutesState.value = 55L
            assertEquals(55L, repository.observeTotalReadingTime().first())
        }

    @Test
    fun observeLibrary_excludesSoftDeletedBooks() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("unused"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("unused"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            fakeDao.upsert(
                BookEntity(
                    id = "active-book",
                    title = "Active",
                    author = "Author",
                    coverPath = null,
                    filePath = "/active.epub",
                    format = "epub",
                    updatedAtEpochMillis = 10L,
                    deletedAtEpochMillis = null,
                ),
            )
            fakeDao.upsert(
                BookEntity(
                    id = "deleted-book",
                    title = "Deleted",
                    author = "Author",
                    coverPath = null,
                    filePath = "/deleted.epub",
                    format = "epub",
                    updatedAtEpochMillis = 20L,
                    deletedAtEpochMillis = null,
                ),
            )
            fakeDao.deleteBook("deleted-book", deletedAt = 30L)

            val books = repository.observeLibrary().first()
            assertEquals(1, books.size)
            assertEquals("active-book", books.first().id)

            val deletedById = repository.observeBookById("deleted-book").firstOrNull()
            assertEquals("deleted-book", deletedById?.id)
        }

    @Test
    fun pagingSource_fiftyBooks_pagesOfTwenty_splitAcrossThreePages() =
        runBlocking {
            // R4: 50 books with pageSize=20 → 3 pages (20, 20, 10).
            val entities =
                (1..50).map { i ->
                    BookEntity(
                        id = "book-$i",
                        title = "Title $i",
                        author = "Author $i",
                        coverPath = null,
                        filePath = "/b$i.epub",
                        format = "epub",
                        updatedAtEpochMillis = i.toLong(),
                    )
                }
            val source = com.nexo.testutil.PagedListPagingSource(entities)

            // Page 1
            val page1 =
                source.load(
                    androidx.paging.PagingSource.LoadParams.Refresh(
                        key = 0,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                )
            val p1 = page1 as androidx.paging.PagingSource.LoadResult.Page<Int, BookEntity>
            assertEquals(20, p1.data.size)
            assertEquals(
                1,
                p1.data
                    .first()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(
                20,
                p1.data
                    .last()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(null, p1.prevKey)
            assertEquals(1, p1.nextKey)

            // Page 2
            val page2 =
                source.load(
                    androidx.paging.PagingSource.LoadParams.Append(
                        key = 1,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                )
            val p2 = page2 as androidx.paging.PagingSource.LoadResult.Page<Int, BookEntity>
            assertEquals(20, p2.data.size)
            assertEquals(
                21,
                p2.data
                    .first()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(
                40,
                p2.data
                    .last()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(0, p2.prevKey)
            assertEquals(2, p2.nextKey)

            // Page 3 (final, smaller)
            val page3 =
                source.load(
                    androidx.paging.PagingSource.LoadParams.Append(
                        key = 2,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                )
            val p3 = page3 as androidx.paging.PagingSource.LoadResult.Page<Int, BookEntity>
            assertEquals(10, p3.data.size)
            assertEquals(
                41,
                p3.data
                    .first()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(
                50,
                p3.data
                    .last()
                    .id
                    .removePrefix("book-")
                    .toInt(),
            )
            assertEquals(1, p3.prevKey)
            assertEquals(null, p3.nextKey)
        }

    @Test
    fun getBookById_returnsBookWhenBookExists() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("unused"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("unused"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            val bookId = "test-book-id"
            fakeDao.upsert(
                BookEntity(
                    id = bookId,
                    title = "Test Book",
                    author = "Test Author",
                    coverPath = null,
                    filePath = "/test/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 100L,
                ),
            )

            val result = repository.getBookById(bookId)
            assertNotNull(result)
            assertEquals(bookId, result?.id)
            assertEquals("Test Book", result?.title)
            assertEquals("Test Author", result?.author)
        }

    @Test
    fun getBookById_returnsNullWhenBookDoesNotExist() =
        runBlocking {
            val fakeDao = FakeBookDao()
            val repository =
                LibraryRepositoryImpl(
                    appContext = mockk(),
                    bookDao = fakeDao,
                    readingProgressDao = FakeReadingProgressDao(),
                    readingStatsDao = FakeReadingStatsDao(),
                    epubParserService = FakeEpubParserService(Result.failure(IllegalStateException("unused"))),
                    pdfParserService = FakePdfParserService(Result.failure(IllegalStateException("unused"))),
                    coverStorage = FakeCoverStorage(),
                    outboxDao = mockk(),
                )

            val result = repository.getBookById("non-existent-id")
            assertEquals(null, result)
        }

    @Test
    fun pagingSource_emptyList_returnsSingleEmptyPage() =
        runBlocking {
            // R4 empty: 0 books → 1 empty page, no error.
            val source = com.nexo.testutil.PagedListPagingSource<BookEntity>(emptyList())
            val result =
                source.load(
                    androidx.paging.PagingSource.LoadParams.Refresh(
                        key = 0,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                )
            val page = result as androidx.paging.PagingSource.LoadResult.Page<Int, BookEntity>
            assertEquals(0, page.data.size)
            assertEquals(null, page.prevKey)
            assertEquals(null, page.nextKey)
        }

    private class FakeEpubParserService(
        private val result: Result<EpubMetadata>,
    ) : EpubParserService {
        override suspend fun extractMetadata(inputStream: java.io.InputStream): Result<EpubMetadata> = result
    }

    private class FakePdfParserService(
        private val result: Result<PdfMetadata>,
    ) : PdfParserService {
        override suspend fun extractMetadata(file: java.io.File): Result<PdfMetadata> = result

        override fun getPageCount(file: java.io.File): Int = 0
    }

    private class FakeReadingProgressDao : ReadingProgressDao {
        override fun observeProgressForBook(bookId: String): Flow<com.nexo.data.local.entity.ReadingProgressEntity?> = MutableStateFlow(null)

        override suspend fun upsert(progress: com.nexo.data.local.entity.ReadingProgressEntity) = Unit

        override suspend fun getProgressForBook(bookId: String): com.nexo.data.local.entity.ReadingProgressEntity? = null

        override suspend fun getAll(): List<com.nexo.data.local.entity.ReadingProgressEntity> = emptyList()

        override fun observeAll(): Flow<List<com.nexo.data.local.entity.ReadingProgressEntity>> = MutableStateFlow(emptyList())

        override suspend fun count(): Int = 0
    }

    private class FakeBookDao : BookDao {
        private val booksState = MutableStateFlow<List<BookEntity>>(emptyList())
        var lastUpserted: BookEntity? = null

        override fun observeAllBooks(): Flow<List<BookEntity>> = booksState.map { books -> books.filter { it.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> =
            booksState.map { books ->
                books.filter { it.deletedAtEpochMillis == null && it.readingState == "reading" }
            }

        override suspend fun upsert(book: BookEntity) {
            lastUpserted = book
            booksState.value =
                booksState.value
                    .filterNot { it.id == book.id }
                    .plus(book)
                    .sortedByDescending { it.updatedAtEpochMillis }
        }

        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(booksState.value.firstOrNull { it.id == bookId })

        override suspend fun getBookById(bookId: String): BookEntity? = booksState.value.firstOrNull { it.id == bookId }

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) {
                        book.copy(
                            updatedAtEpochMillis = deletedAt,
                            deletedAtEpochMillis = deletedAt,
                        )
                    } else {
                        book
                    }
                }
        }

        override suspend fun updateRating(
            bookId: String,
            rating: Int?,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) book.copy(userRating = rating) else book
                }
        }

        override suspend fun updateStatus(
            bookId: String,
            status: String?,
            updatedAt: Long,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) book.copy(status = status, updatedAtEpochMillis = updatedAt) else book
                }
        }

        override suspend fun deleteById(bookId: String) {
            booksState.value = booksState.value.filterNot { it.id == bookId }
        }

        override suspend fun startReading(
            bookId: String,
            updatedAt: Long,
        ) {}

        override suspend fun updateReadingProgress(
            bookId: String,
            progress: Float,
            updatedAt: Long,
        ) {}

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
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) {
                        book.copy(
                            title = title,
                            author = author,
                            description = description,
                            coverPath = coverPath,
                            genre = genre,
                            language = language,
                            publisher = publisher,
                            tags = tags,
                            publishedDate = publishedDate,
                            updatedAtEpochMillis = updatedAt,
                        )
                    } else {
                        book
                    }
                }
        }

        override suspend fun count(): Int = booksState.value.size

        override fun observeAllBooksPaged(): PagingSource<Int, BookEntity> = FakePagingSource(emptyList())
    }

    private class FakeCoverStorage : CoverStorage {
        override suspend fun saveCover(
            bookId: String,
            coverBytes: ByteArray,
        ): Result<String> = Result.success("/tmp/$bookId.jpg")

        override suspend fun deleteCover(bookId: String): Result<Unit> = Result.success(Unit)
    }

    private class FailingCoverStorage : CoverStorage {
        override suspend fun saveCover(
            bookId: String,
            coverBytes: ByteArray,
        ): Result<String> = Result.failure(IllegalStateException("disk full"))

        override suspend fun deleteCover(bookId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeReadingStatsDao : ReadingStatsDao {
        val totalMinutesState = MutableStateFlow<Long?>(0L)

        override fun observeStatsForBook(bookId: String): Flow<ReadingStatsEntity?> = MutableStateFlow(null)

        override fun observeAllStats(): Flow<List<ReadingStatsEntity>> = MutableStateFlow(emptyList())

        override suspend fun upsert(stats: ReadingStatsEntity) = Unit

        override fun observeTotalMinutesRead(): Flow<Long?> = totalMinutesState

        override fun observeTotalMinutesReadForUser(userId: String): Flow<Long?> = totalMinutesState

        override suspend fun deleteForBook(bookId: String) = Unit
    }
}

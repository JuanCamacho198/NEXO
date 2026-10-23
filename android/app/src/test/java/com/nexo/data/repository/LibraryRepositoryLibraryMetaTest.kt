package com.nexo.data.repository

import androidx.paging.PagingSource
import com.nexo.data.epub.EpubMetadata
import com.nexo.data.epub.EpubParserService
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.StoredBookPath
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.pdf.PdfMetadata
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.remote.addons.LibraryMetaSource
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.testutil.FakePagingSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Library-side metadata enrichment: the import path enriches the row it just
 * wrote, and the explicit per-book refresh re-asks with the cache bypassed.
 */
class LibraryRepositoryLibraryMetaTest {
    private fun repository(
        dao: FakeBookDao,
        outboxDao: SyncOutboxDao,
        libraryMeta: LibraryMetaSource? = null,
    ): LibraryRepositoryImpl =
        LibraryRepositoryImpl(
            appContext = mockk(),
            bookDao = dao,
            readingProgressDao = FakeReadingProgressDao(),
            readingStatsDao = FakeReadingStatsDao(),
            epubParserService = FakeEpubParserService(Result.success(EpubMetadata(title = "Pride and Prejudice", author = "Jane Austen", coverImageBytes = null))),
            pdfParserService = FakePdfParserService(Result.success(PdfMetadata("PDF Book", null, 10, 100))),
            coverStorage = FakeCoverStorage(),
            outboxDao = outboxDao,
            libraryMeta = { libraryMeta },
        )

    @Test
    fun importBookFromEpub_writesTheMergedFieldsOntoTheRow() =
        runBlocking {
            val dao = FakeBookDao()
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val meta = FakeLibraryMetaSource { book, _ -> book.copy(description = "A novel", publisher = "Penguin") }

            val result =
                repository(dao, outboxDao, meta).importBookFromEpub(
                    request = BookImportRequest(sourcePath = "content://books/1342.epub", sourceItemId = "gutenberg:1342"),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            assertEquals("A novel", result.getOrNull()?.description)
            assertEquals("A novel", dao.metadata?.description)
            assertEquals("Penguin", dao.metadata?.publisher)
            // The enrichment happens before the single CREATE the import queues.
            coVerify(exactly = 1) { outboxDao.insert(any()) }
        }

    @Test
    fun importBookFromEpub_survivesAnEnrichmentThatThrows() =
        runBlocking {
            val dao = FakeBookDao()
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val meta = FakeLibraryMetaSource { _, _ -> throw IllegalStateException("addon exploded") }

            val result =
                repository(dao, outboxDao, meta).importBookFromEpub(
                    request = BookImportRequest(sourcePath = "content://books/1342.epub", sourceItemId = "gutenberg:1342"),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull()?.description)
            coVerify(exactly = 1) { outboxDao.insert(any()) }
        }

    @Test
    fun importBookFromEpub_doesNothingWhenNoMetadataSourceIsWired() =
        runBlocking {
            val dao = FakeBookDao()

            val result =
                repository(dao, mockk<SyncOutboxDao>(relaxed = true)).importBookFromEpub(
                    request = BookImportRequest(sourcePath = "content://books/1342.epub", sourceItemId = "gutenberg:1342"),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            assertNull(dao.metadata)
        }

    @Test
    fun refreshLibraryMetadata_forcesTheAskAndQueuesAnUpdate() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(ownedEntity("book-1"))
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val meta = FakeLibraryMetaSource { book, _ -> book.copy(description = "A novel") }

            val result = repository(dao, outboxDao, meta).refreshLibraryMetadata("book-1")

            assertTrue(result.getOrNull() == true)
            assertEquals(listOf("book-1" to true), meta.calls)
            assertEquals("A novel", dao.metadata?.description)
            coVerify { outboxDao.insert(match<SyncOutboxEntity> { it.operation == SyncOperation.UPDATE.name }) }
        }

    @Test
    fun refreshLibraryMetadata_reportsNoChangeWhenTheAddonsAddNothing() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(ownedEntity("book-1"))
            val meta = FakeLibraryMetaSource { _, _ -> null }

            val result = repository(dao, mockk<SyncOutboxDao>(relaxed = true), meta).refreshLibraryMetadata("book-1")

            assertFalse(result.getOrNull() ?: true)
            assertNull(dao.metadata)
        }

    @Test
    fun refreshLibraryMetadata_isFalseForAnUnknownBook() =
        runBlocking {
            val meta = FakeLibraryMetaSource { book, _ -> book }

            val result = repository(FakeBookDao(), mockk<SyncOutboxDao>(relaxed = true), meta).refreshLibraryMetadata("missing")

            assertFalse(result.getOrNull() ?: true)
            assertTrue(meta.calls.isEmpty())
        }

    private fun ownedEntity(id: String) =
        BookEntity(
            id = id,
            title = "Pride and Prejudice",
            author = "Jane Austen",
            coverPath = null,
            filePath = "/books/$id.epub",
            format = "epub",
            totalPages = 432,
            updatedAtEpochMillis = 10L,
            sourceAddonId = "0123456789abcdef",
            sourceItemId = "gutenberg:1342",
            isbn13 = "9780141439518",
        )

    private class FakeLibraryMetaSource(
        private val respond: (Book, Boolean) -> Book?,
    ) : LibraryMetaSource {
        val calls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun enrich(
            book: Book,
            force: Boolean,
        ): Book? {
            calls += book.id to force
            return respond(book, force)
        }
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

    private class FakeCoverStorage : CoverStorage {
        override suspend fun saveCover(
            bookId: String,
            coverBytes: ByteArray,
        ): Result<String> = Result.success("/covers/$bookId.jpg")

        override suspend fun deleteCover(bookId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeReadingProgressDao : ReadingProgressDao {
        override fun observeProgressForBook(bookId: String): Flow<ReadingProgressEntity?> = MutableStateFlow(null)

        override suspend fun upsert(progress: ReadingProgressEntity) = Unit

        override suspend fun getProgressForBook(bookId: String): ReadingProgressEntity? = null

        override suspend fun getAll(): List<ReadingProgressEntity> = emptyList()

        override fun observeAll(): Flow<List<ReadingProgressEntity>> = MutableStateFlow(emptyList())

        override suspend fun count(): Int = 0
    }

    private class FakeReadingStatsDao : ReadingStatsDao {
        override fun observeStatsForBook(bookId: String): Flow<ReadingStatsEntity?> = MutableStateFlow(null)

        override fun observeAllStats(): Flow<List<ReadingStatsEntity>> = MutableStateFlow(emptyList())

        override suspend fun upsert(stats: ReadingStatsEntity) = Unit

        override fun observeTotalMinutesRead(): Flow<Long?> = MutableStateFlow(0L)

        override fun observeTotalMinutesReadForUser(userId: String): Flow<Long?> = MutableStateFlow(0L)

        override suspend fun deleteForBook(bookId: String) = Unit
    }

    private class FakeBookDao : BookDao {
        private val booksState = MutableStateFlow<List<BookEntity>>(emptyList())
        var metadata: BookEntity? = null

        override fun observeAllBooks(): Flow<List<BookEntity>> = booksState.map { books -> books.filter { it.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> = observeAllBooks()

        override fun observeAllBooksPaged(): PagingSource<Int, BookEntity> = FakePagingSource(emptyList())

        override suspend fun upsert(book: BookEntity) {
            booksState.value = booksState.value.filterNot { it.id == book.id }.plus(book)
        }

        override suspend fun upsertAll(books: List<BookEntity>) = books.forEach { upsert(it) }

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(booksState.value.firstOrNull { it.id == bookId })

        override suspend fun getBookById(bookId: String): BookEntity? = booksState.value.firstOrNull { it.id == bookId }

        override suspend fun getBySourceItemId(sourceItemId: String): BookEntity? =
            booksState.value.firstOrNull {
                it.sourceItemId == sourceItemId && it.deletedAtEpochMillis == null
            }

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) book.copy(deletedAtEpochMillis = deletedAt) else book
                }
        }

        override suspend fun deleteById(bookId: String) {
            booksState.value = booksState.value.filterNot { it.id == bookId }
        }

        override suspend fun updateRating(
            bookId: String,
            rating: Int?,
        ) = Unit

        override suspend fun updateStatus(
            bookId: String,
            status: String?,
            updatedAt: Long,
        ) = Unit

        override suspend fun startReading(
            bookId: String,
            updatedAt: Long,
        ) = Unit

        override suspend fun updateReadingProgress(
            bookId: String,
            progress: Float,
            updatedAt: Long,
        ) = Unit

        override suspend fun completeReading(
            bookId: String,
            updatedAt: Long,
        ) = Unit

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
            val current = booksState.value.firstOrNull { it.id == bookId }
            metadata =
                current?.copy(
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
            metadata?.let { merged ->
                booksState.value = booksState.value.map { if (it.id == bookId) merged else it }
            }
        }

        override suspend fun count(): Int = booksState.value.size

        override suspend fun allStoredPaths(): List<StoredBookPath> = emptyList()

        override suspend fun updateFilePath(
            bookId: String,
            filePath: String,
        ) = Unit
    }
}

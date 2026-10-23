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
import com.nexo.data.pdf.PdfMetadata
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.BookImportRequest
import com.nexo.testutil.FakePagingSource
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Provenance contract for addon-sourced books: the row keeps the addon id, the
 * frozen protocol item id and the ISBN, and [LibraryRepositoryImpl.findOwned]
 * answers the pre-download ownership check from those columns.
 */
class BookProvenanceTest {
    private val addonId = "0123456789abcdef"
    private val sourceItemId = "gutenberg:1342"

    private fun repository(dao: FakeBookDao): LibraryRepositoryImpl =
        LibraryRepositoryImpl(
            appContext = mockk(),
            bookDao = dao,
            readingProgressDao = FakeReadingProgressDao(),
            readingStatsDao = FakeReadingStatsDao(),
            epubParserService = FakeEpubParserService(Result.success(EpubMetadata(title = "Pride and Prejudice", author = "Jane Austen", coverImageBytes = null))),
            pdfParserService = FakePdfParserService(Result.success(PdfMetadata("PDF Book", null, 10, 100))),
            coverStorage = FakeCoverStorage(),
            outboxDao = mockk(),
        )

    @Test
    fun importBookFromEpub_writesProvenanceFromRequestOntoRow() =
        runBlocking {
            val dao = FakeBookDao()

            val result =
                repository(dao).importBookFromEpub(
                    request =
                        BookImportRequest(
                            sourcePath = "content://books/1342.epub",
                            fallbackTitle = "1342.epub",
                            sourceAddonId = addonId,
                            sourceItemId = sourceItemId,
                            isbn13 = "9780141439518",
                        ),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            val inserted = dao.lastUpserted
            assertNotNull(inserted)
            assertEquals(addonId, inserted?.sourceAddonId)
            assertEquals(sourceItemId, inserted?.sourceItemId)
            assertEquals("9780141439518", inserted?.isbn13)
            assertEquals(sourceItemId, result.getOrNull()?.sourceItemId)
        }

    @Test
    fun importBookFromEpub_leavesProvenanceNullWhenCallerOmitsIt() =
        runBlocking {
            val dao = FakeBookDao()

            val result =
                repository(dao).importBookFromEpub(
                    request = BookImportRequest(sourcePath = "content://books/local.epub", fallbackTitle = "local.epub"),
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )

            assertTrue(result.isSuccess)
            val inserted = dao.lastUpserted
            assertNotNull(inserted)
            assertNull(inserted?.sourceAddonId)
            assertNull(inserted?.sourceItemId)
            assertNull(inserted?.isbn13)
        }

    @Test
    fun findOwned_returnsBookCarryingTheSourceItemId() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(ownedEntity(id = "owned-book", sourceItemId = sourceItemId))
            dao.upsert(ownedEntity(id = "other-book", sourceItemId = "gutenberg:84"))

            val owned = repository(dao).findOwned(sourceItemId)

            assertEquals("owned-book", owned?.id)
            assertEquals(sourceItemId, owned?.sourceItemId)
        }

    @Test
    fun findOwned_returnsNullWhenNoBookCarriesTheItemId() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(ownedEntity(id = "owned-book", sourceItemId = "gutenberg:84"))

            assertNull(repository(dao).findOwned(sourceItemId))
        }

    @Test
    fun findOwned_ignoresSoftDeletedBooks() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(ownedEntity(id = "deleted-book", sourceItemId = sourceItemId))
            dao.deleteBook("deleted-book", deletedAt = 99L)

            assertNull(repository(dao).findOwned(sourceItemId))
        }

    private fun ownedEntity(
        id: String,
        sourceItemId: String,
    ) = BookEntity(
        id = id,
        title = "Pride and Prejudice",
        author = "Jane Austen",
        coverPath = null,
        filePath = "/books/$id.epub",
        format = "epub",
        updatedAtEpochMillis = 10L,
        sourceAddonId = addonId,
        sourceItemId = sourceItemId,
        isbn13 = "9780141439518",
    )

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
        var lastUpserted: BookEntity? = null

        override fun observeAllBooks(): Flow<List<BookEntity>> =
            booksState.map { books -> books.filter { it.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> = observeAllBooks()

        override fun observeAllBooksPaged(): PagingSource<Int, BookEntity> = FakePagingSource(emptyList())

        override suspend fun upsert(book: BookEntity) {
            lastUpserted = book
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
        ) = Unit

        override suspend fun count(): Int = booksState.value.size

        override suspend fun allStoredPaths(): List<StoredBookPath> = emptyList()

        override suspend fun updateFilePath(
            bookId: String,
            filePath: String,
        ) = Unit
    }
}

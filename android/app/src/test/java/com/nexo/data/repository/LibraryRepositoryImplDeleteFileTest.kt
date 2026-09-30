package com.nexo.data.repository

import com.nexo.data.epub.EpubParserService
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.sync.SyncSettleGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies the slice-6b delete-path contract: the local backing file is removed
 * only after the sync-settle gate reports settled, never for a path another live
 * book still references, and not at all for external/`content://` paths.
 */
class LibraryRepositoryImplDeleteFileTest {
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("nexo-delete-file-test").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun settled_deletesBackingFile() =
        runBlocking {
            val bookId = "book-1"
            val backing = backingFile("book-1.epub")
            val gate = FakeSettleGate(settled = true)
            val bookDao = mockk<BookDao>(relaxed = true)
            coEvery { bookDao.getBookById(bookId) } returns bookEntity(bookId, backing.absolutePath)
            every { bookDao.observeAllBooks() } returns flowOf(emptyList())
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val repository = repository(bookDao, outboxDao, gate)

            val result = repository.deleteBook(bookId)

            assertTrue(result.isSuccess)
            assertFalse("backing file must be deleted once sync has settled", backing.exists())
            coVerify { bookDao.deleteBook(bookId, any()) }
            coVerify { outboxDao.insert(match { it.operation == SyncOperation.DELETE.name }) }
        }

    @Test
    fun driveBacked_deleteLocalOnly_removesLocalFileWithoutTombstone() =
        runBlocking {
            val bookId = "drive-book"
            val backing = backingFile("drive-book.epub")
            val gate = FakeSettleGate(settled = true)
            val bookDao = mockk<BookDao>(relaxed = true)
            coEvery { bookDao.getBookById(bookId) } returns bookEntity(bookId, backing.absolutePath)
            every { bookDao.observeAllBooks() } returns flowOf(emptyList())
            val outboxDao = mockk<SyncOutboxDao>(relaxed = true)
            val repository = repository(bookDao, outboxDao, gate)

            val result = repository.deleteBookLocalOnly(bookId)

            assertTrue(result.isSuccess)
            assertFalse("local bytes are removed for a Drive-backed book", backing.exists())
            // Local-only delete never queues a cloud DELETE tombstone (Drive bytes stay).
            coVerify(exactly = 0) {
                outboxDao.insert(match { it.operation == SyncOperation.DELETE.name })
            }
        }

    @Test
    fun gateFalse_fileRetainedForSweep() =
        runBlocking {
            val bookId = "pending-book"
            val backing = backingFile("pending-book.epub")
            val gate = FakeSettleGate(settled = false)
            val bookDao = mockk<BookDao>(relaxed = true)
            coEvery { bookDao.getBookById(bookId) } returns bookEntity(bookId, backing.absolutePath)
            every { bookDao.observeAllBooks() } returns flowOf(emptyList())
            val repository = repository(bookDao, mockk(relaxed = true), gate)

            val result = repository.deleteBook(bookId)

            assertTrue(result.isSuccess)
            assertTrue("file must be retained when sync has not settled", backing.exists())
            assertEquals("gate must be consulted exactly once", 1, gate.awaitCount)
        }

    @Test
    fun pathSharedWithLiveBook_fileRetained() =
        runBlocking {
            val bookId = "shared-a"
            val backing = backingFile("shared.epub")
            val gate = FakeSettleGate(settled = true)
            val bookDao = mockk<BookDao>(relaxed = true)
            coEvery { bookDao.getBookById(bookId) } returns bookEntity(bookId, backing.absolutePath)
            // A second live row still points at the same path.
            every { bookDao.observeAllBooks() } returns
                flowOf(
                    listOf(bookEntity("shared-b", backing.absolutePath)),
                )
            val repository = repository(bookDao, mockk(relaxed = true), gate)

            repository.deleteBook(bookId)

            assertTrue("a path referenced by another book must not be deleted", backing.exists())
        }

    @Test
    fun contentUriBacked_delete_leavesExternalPathUntouched() =
        runBlocking {
            val bookId = "saf-book"
            val gate = FakeSettleGate(settled = true)
            val bookDao = mockk<BookDao>(relaxed = true)
            coEvery { bookDao.getBookById(bookId) } returns bookEntity(bookId, "content://books/saf.epub")
            every { bookDao.observeAllBooks() } returns flowOf(emptyList())
            val repository = repository(bookDao, mockk(relaxed = true), gate)

            val result = repository.deleteBook(bookId)

            assertTrue(result.isSuccess)
            assertEquals("non-file paths never consult the gate", 0, gate.awaitCount)
        }

    private fun repository(
        bookDao: BookDao,
        outboxDao: SyncOutboxDao,
        gate: SyncSettleGate,
    ): LibraryRepositoryImpl =
        LibraryRepositoryImpl(
            appContext = mockk { every { filesDir } returns tempRoot },
            bookDao = bookDao,
            readingStatsDao = mockk<ReadingStatsDao>(relaxed = true),
            epubParserService = mockk<EpubParserService>(relaxed = true),
            pdfParserService = mockk<PdfParserService>(relaxed = true),
            coverStorage = FakeCoverStorage(),
            readingProgressDao = mockk<ReadingProgressDao>(relaxed = true),
            outboxDao = outboxDao,
            settleGate = gate,
        )

    /** A backing file under the managed `catalog` dir inside [tempRoot]. */
    private fun backingFile(name: String): File {
        val catalog = File(tempRoot, "catalog").apply { mkdirs() }
        return File(catalog, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun bookEntity(
        id: String,
        filePath: String,
    ) = BookEntity(
        id = id,
        title = "Book $id",
        author = "Author",
        coverPath = null,
        filePath = filePath,
        format = "epub",
        updatedAtEpochMillis = 1L,
    )

    private class FakeSettleGate(
        private val settled: Boolean,
    ) : SyncSettleGate {
        var awaitCount = 0
            private set

        override suspend fun awaitSettled(): Boolean {
            awaitCount++
            return settled
        }
    }

    private class FakeCoverStorage : CoverStorage {
        override suspend fun saveCover(
            bookId: String,
            coverBytes: ByteArray,
        ): Result<String> = Result.success("/tmp/$bookId.jpg")

        override suspend fun deleteCover(bookId: String): Result<Unit> = Result.success(Unit)
    }
}

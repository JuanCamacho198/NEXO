package com.nexo.data.repository

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.domain.sync.SyncSettleGate
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
 * Verifies the slice-6b orphan-sweep contract: only unreferenced files past the
 * grace window are deleted; in-flight `*.part` downloads are never candidates;
 * a sweep is skipped entirely while sync has not settled.
 */
class StorageRepositoryImplTest {
    private lateinit var root: File
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nexo-sweep-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun sweep_deletesUnreferencedFilesPastGrace() =
        runBlocking {
            val orphan = fileIn("catalog", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles()

            assertEquals(1, deleted)
            assertFalse(orphan.exists())
        }

    @Test
    fun sweep_excludesPartFiles() =
        runBlocking {
            val part = fileIn("catalog", "download.part", ageMillis = 10 * 60 * 1000L)
            val orphan = fileIn("catalog", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles()

            assertEquals("only the non-part orphan is swept", 1, deleted)
            assertTrue("in-flight .part files must never be swept", part.exists())
            assertFalse(orphan.exists())
        }

    @Test
    fun sweep_respectsGraceWindow() =
        runBlocking {
            val fresh = fileIn("pdfs", "fresh.pdf", ageMillis = 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles()

            assertEquals(0, deleted)
            assertTrue("a file inside the grace window is skipped", fresh.exists())
        }

    @Test
    fun sweep_skipsWhenSyncNotSettled() =
        runBlocking {
            val orphan = fileIn("epubs", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = false, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles()

            assertEquals(0, deleted)
            assertTrue("no sweep while sync has not settled", orphan.exists())
        }

    @Test
    fun sweep_keepsFilesReferencedByLiveBooks() =
        runBlocking {
            val referenced = fileIn("catalog", "referenced.epub", ageMillis = 10 * 60 * 1000L)
            val repository =
                repository(
                    settled = true,
                    liveBooks = listOf(bookEntity(filePath = referenced.absolutePath)),
                )

            val deleted = repository.sweepOrphanBookFiles()

            assertEquals(0, deleted)
            assertTrue("a file a live book references is not an orphan", referenced.exists())
        }

    // ── Slice 7: per-book sizing ────────────────────────────────────────

    @Test
    fun bookStorageUsage_sumsBookFileAndCover() =
        runBlocking {
            val file = fileIn("catalog", "book.epub", ageMillis = 0L, sizeBytes = 100)
            val cover = fileIn("covers", "book.jpg", ageMillis = 0L, sizeBytes = 40)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(
                            bookEntity(
                                id = "b1",
                                title = "Book One",
                                filePath = file.absolutePath,
                                coverPath = cover.absolutePath,
                            ),
                        ),
                )

            val usage = repository.bookStorageUsage()

            assertEquals(1, usage.size)
            assertEquals("b1", usage[0].bookId)
            assertEquals("Book One", usage[0].title)
            assertEquals(140L, usage[0].sizeBytes)
        }

    @Test
    fun bookStorageUsage_dedupesCoverEqualToBackingFile() =
        runBlocking {
            val file = fileIn("catalog", "book.epub", ageMillis = 0L, sizeBytes = 100)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(
                            bookEntity(id = "b1", filePath = file.absolutePath, coverPath = file.absolutePath),
                        ),
                )

            val usage = repository.bookStorageUsage()

            assertEquals("a path counted twice must be deduped", 100L, usage[0].sizeBytes)
        }

    @Test
    fun bookStorageUsage_missingFilesContributeZero() =
        runBlocking {
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(
                            bookEntity(id = "b1", filePath = File(root, "catalog/ghost.epub").absolutePath),
                        ),
                )

            val usage = repository.bookStorageUsage()

            assertEquals(0L, usage[0].sizeBytes)
        }

    @Test
    fun bookStorageUsage_sharedFileCountedOnceAcrossBooks() =
        runBlocking {
            val shared = fileIn("catalog", "shared.epub", ageMillis = 0L, sizeBytes = 250)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(
                            bookEntity(id = "b1", filePath = shared.absolutePath),
                            bookEntity(id = "b2", filePath = shared.absolutePath),
                        ),
                )

            val usage = repository.bookStorageUsage()

            assertEquals(2, usage.size)
            assertEquals("shared bytes are attributed once", 250L, usage.sumOf { it.sizeBytes })
        }

    @Test
    fun bookStorageUsage_totalsMatchUniqueBytes() =
        runBlocking {
            val a = fileIn("catalog", "a.epub", ageMillis = 0L, sizeBytes = 100)
            val b = fileIn("pdfs", "b.pdf", ageMillis = 0L, sizeBytes = 250)
            val aCover = fileIn("covers", "a.jpg", ageMillis = 0L, sizeBytes = 50)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(
                            bookEntity(id = "b1", filePath = a.absolutePath, coverPath = aCover.absolutePath),
                            bookEntity(id = "b2", filePath = b.absolutePath),
                        ),
                )

            val usage = repository.bookStorageUsage()

            assertEquals(400L, usage.sumOf { it.sizeBytes })
        }

    @Test
    fun bookStorageUsage_emptyLibraryReturnsEmpty() =
        runBlocking {
            val repository = repository(settled = true, liveBooks = emptyList())

            assertTrue(repository.bookStorageUsage().isEmpty())
        }

    private fun repository(
        settled: Boolean,
        liveBooks: List<BookEntity>,
    ): StorageRepositoryImpl {
        val bookDao = mockk<BookDao>(relaxed = true)
        every { bookDao.observeAllBooks() } returns flowOf(liveBooks)
        return StorageRepositoryImpl(
            appContext = mockk { every { filesDir } returns root },
            bookDao = bookDao,
            settleGate = FakeSettleGate(settled),
            nowMillis = { fixedNow },
        )
    }

    /** Creates an aged regular file under `root/<dir>`; [ageMillis] before [fixedNow]. */
    private fun fileIn(
        dir: String,
        name: String,
        ageMillis: Long,
        sizeBytes: Int = 3,
    ): File {
        val directory = File(root, dir).apply { mkdirs() }
        return File(directory, name).apply {
            writeBytes(ByteArray(sizeBytes))
            setLastModified(fixedNow - ageMillis)
        }
    }

    private fun bookEntity(
        filePath: String,
        id: String = "live-book",
        title: String = "Live Book",
        coverPath: String? = null,
    ) = BookEntity(
        id = id,
        title = title,
        author = "Author",
        coverPath = coverPath,
        filePath = filePath,
        format = "epub",
        updatedAtEpochMillis = 1L,
    )

    private class FakeSettleGate(
        private val settled: Boolean,
    ) : SyncSettleGate {
        override suspend fun awaitSettled(): Boolean = settled
    }
}

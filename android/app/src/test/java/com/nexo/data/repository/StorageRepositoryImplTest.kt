package com.nexo.data.repository

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.domain.sync.SyncSettleGate
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
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
            val orphan = fileIn("books", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(1, deleted)
            assertFalse(orphan.exists())
        }

    @Test
    fun sweep_excludesPartFiles() =
        runBlocking {
            val part = fileIn("books", "download.part", ageMillis = 10 * 60 * 1000L)
            val orphan = fileIn("books", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals("only the non-part orphan is swept", 1, deleted)
            assertTrue("in-flight .part files must never be swept", part.exists())
            assertFalse(orphan.exists())
        }

    @Test
    fun sweep_respectsGraceWindow() =
        runBlocking {
            val fresh = fileIn("pdfs", "fresh.pdf", ageMillis = 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(0, deleted)
            assertTrue("a file inside the grace window is skipped", fresh.exists())
        }

    @Test
    fun sweep_skipsWhenSyncNotSettled() =
        runBlocking {
            val orphan = fileIn("epubs", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = false, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(0, deleted)
            assertTrue("no sweep while sync has not settled", orphan.exists())
        }

    @Test
    fun sweep_keepsFilesReferencedByLiveBooks() =
        runBlocking {
            val referenced = fileIn("books", "referenced.epub", ageMillis = 10 * 60 * 1000L)
            val repository =
                repository(
                    settled = true,
                    liveBooks = listOf(bookEntity(filePath = referenced.absolutePath)),
                )

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(0, deleted)
            assertTrue("a file a live book references is not an orphan", referenced.exists())
        }

    // ── WU6 (FR-04): sweep covers every managed directory ───────────────

    @Test
    fun sweep_removesOrphansInBooksAndCovers() =
        runBlocking {
            val bookOrphan = fileIn("books", "orphan.epub", ageMillis = 10 * 60 * 1000L)
            val coverOrphan = fileIn("covers", "orphan.jpg", ageMillis = 10 * 60 * 1000L)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(2, deleted)
            assertFalse(bookOrphan.exists())
            assertFalse(coverOrphan.exists())
        }

    @Test
    fun sweep_keepsCoversReferencedByLiveBooks() =
        runBlocking {
            val book = fileIn("books", "book.epub", ageMillis = 10 * 60 * 1000L)
            val cover = fileIn("covers", "book.jpg", ageMillis = 10 * 60 * 1000L)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(bookEntity(filePath = book.absolutePath, coverPath = cover.absolutePath)),
                )

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals(0, deleted)
            assertTrue("a referenced cover is never an orphan", cover.exists())
            assertTrue(book.exists())
        }

    @Test
    fun sweep_cleanLibraryReportsZero() =
        runBlocking {
            val book = fileIn("books", "book.epub", ageMillis = 10 * 60 * 1000L)
            val cover = fileIn("covers", "book.jpg", ageMillis = 10 * 60 * 1000L)
            val repository =
                repository(
                    settled = true,
                    liveBooks =
                        listOf(bookEntity(filePath = book.absolutePath, coverPath = cover.absolutePath)),
                )

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals("clean library sweeps to zero", 0, deleted)
            assertTrue(book.exists())
            assertTrue(cover.exists())
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

            val usage = repository.bookStorageUsage(TEST_USER_ID)

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

            val usage = repository.bookStorageUsage(TEST_USER_ID)

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

            val usage = repository.bookStorageUsage(TEST_USER_ID)

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

            val usage = repository.bookStorageUsage(TEST_USER_ID)

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

            val usage = repository.bookStorageUsage(TEST_USER_ID)

            assertEquals(400L, usage.sumOf { it.sizeBytes })
        }

    @Test
    fun bookStorageUsage_emptyLibraryReturnsEmpty() =
        runBlocking {
            val repository = repository(settled = true, liveBooks = emptyList())

            assertTrue(repository.bookStorageUsage(TEST_USER_ID).isEmpty())
        }

    // ── T9: the sweep never crosses accounts ────────────────────────────

    @Test
    fun sweep_leavesAnotherAccountsFilesAlone() =
        runBlocking {
            val mine = fileIn("books", "mine.epub", ageMillis = 10 * 60 * 1000L)
            val theirs = fileIn("books", "theirs.epub", ageMillis = 10 * 60 * 1000L, userId = OTHER_USER_ID)
            val repository = repository(settled = true, liveBooks = emptyList())

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals("only this account's orphan is swept", 1, deleted)
            assertFalse(mine.exists())
            assertTrue("another account's file is never touched", theirs.exists())
        }

    @Test
    fun sweep_isNotSteeredByAnotherAccountsRows() =
        runBlocking {
            // The other account still references a file that sits in OUR subtree
            // (e.g. a stale copy). Its row must not preserve it for us.
            val stale = fileIn("books", "stale.epub", ageMillis = 10 * 60 * 1000L)
            val repository =
                repository(
                    settled = true,
                    liveBooks = listOf(bookEntity(id = "theirs", filePath = stale.absolutePath, userId = OTHER_USER_ID)),
                )

            val deleted = repository.sweepOrphanBookFiles(TEST_USER_ID)

            assertEquals("another account's row cannot keep our file alive", 1, deleted)
            assertFalse(stale.exists())
        }

    @Test
    fun bookStorageUsage_ignoresAnotherAccountsSubtrees() =
        runBlocking {
            fileIn("books", "theirs.epub", ageMillis = 0L, sizeBytes = 999, userId = OTHER_USER_ID)
            val repository = repository(settled = true, liveBooks = emptyList())

            assertTrue(repository.bookStorageUsage(TEST_USER_ID).isEmpty())
        }

    private fun repository(
        settled: Boolean,
        liveBooks: List<BookEntity>,
    ): StorageRepositoryImpl {
        val bookDao = mockk<BookDao>(relaxed = true)
        // Mirror the real DAO: the predicate is part of the query, so a row
        // owned by another account is never returned for this one.
        every { bookDao.observeAllBooks(any()) } answers {
            val userId = firstArg<String>()
            flowOf(liveBooks.filter { it.userId == userId })
        }
        return StorageRepositoryImpl(
            appContext = mockk { every { filesDir } returns root },
            bookDao = bookDao,
            settleGate = FakeSettleGate(settled),
            nowMillis = { fixedNow },
        )
    }

    /**
     * Creates an aged regular file under `root/<dir>/<userId>`; [ageMillis]
     * before [fixedNow]. The sweep only walks the active user's subtree (T9),
     * so the fixture must live there too.
     */
    private fun fileIn(
        dir: String,
        name: String,
        ageMillis: Long,
        sizeBytes: Int = 3,
        userId: String = TEST_USER_ID,
    ): File {
        val directory = File(File(root, dir), userId).apply { mkdirs() }
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
        userId: String = TEST_USER_ID,
    ) = BookEntity(
        id = id,
        title = title,
        author = "Author",
        coverPath = coverPath,
        filePath = filePath,
        format = "epub",
        updatedAtEpochMillis = 1L,
        userId = userId,
    )

    private class FakeSettleGate(
        private val settled: Boolean,
    ) : SyncSettleGate {
        override suspend fun awaitSettled(): Boolean = settled
    }
}


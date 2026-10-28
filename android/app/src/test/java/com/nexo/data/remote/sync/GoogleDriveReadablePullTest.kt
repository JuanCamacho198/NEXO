package com.nexo.data.remote.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.session.SessionManager
import com.nexo.domain.model.AuthSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The push now uploads NEW books under the readable Drive name
 * (`Title -- <id>.epub`, commit a405f987) while the local book keeps the
 * CANONICAL `remotePath`. The pull must still resolve a readable-named remote
 * object back to its book id, or a book uploaded from one device can never be
 * pulled by the other.
 */
class GoogleDriveReadablePullTest {
    private lateinit var bookDao: BookDao
    private lateinit var mappingDao: SyncFileMappingDao
    private lateinit var sessionManager: SessionManager
    private lateinit var remote: StorageSyncRemoteDataSource
    private lateinit var localDir: File
    private val session = AuthSession(userId = "u1", email = "a@e.x")
    private val bookId = "5b4ba1c1-1b6f-4301-816a-40385be603fa"

    @Before fun setUp() {
        bookDao = mockk(relaxed = true)
        mappingDao = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        remote = mockk(relaxed = true)
        localDir = createTempDir()
        coEvery { sessionManager.ensureFreshSession() } returns Result.success(session)
        coEvery { mappingDao.getByUserId(any()) } returns emptyList()
        coEvery { mappingDao.getByDriveFileId(any()) } returns null
    }

    @After fun tearDown() {
        localDir.deleteRecursively()
        unmockkAll()
    }

    private fun service() =
        GoogleDriveSyncService(
            outboxDao = mockk<SyncOutboxDao>(relaxed = true),
            bookDao = bookDao,
            mappingDao = mappingDao,
            highlightDao = mockk<HighlightDao>(relaxed = true),
            bookmarkDao = mockk<BookmarkDao>(relaxed = true),
            sessionManager = sessionManager,
            remoteDataSource = remote,
            localBooksDir = localDir,
            isEnabled = { true },
            tokenRefresher = { Result.success("token") },
        )

    @Test fun pull_downloadsBookStoredUnderReadableDriveName() =
        runBlocking {
            val remoteName = "Prisioneros de la geografía -- $bookId.epub"
            coEvery { remote.list(any()) } returns listOf("books/u1/$remoteName")
            coEvery { remote.download(any()) } returns byteArrayOf(1, 2, 3)
            val existing =
                BookEntity(
                    id = bookId,
                    title = "Prisioneros de la geografía",
                    author = null,
                    coverPath = null,
                    filePath = File(localDir, "$bookId.epub").absolutePath,
                    format = "epub",
                    updatedAtEpochMillis = 1,
                    deletedAtEpochMillis = null,
                )
            // A book pushed by the OTHER device carries no local file yet, so the
            // pull has to fetch it. The mapping is keyed by the canonical path.
            coEvery { bookDao.getBookById(bookId, "u1") } returns existing

            assertTrue(service().schedulePull().isSuccess)

            coVerify(exactly = 1) { remote.download("books/u1/$remoteName") }
            assertEquals(
                "the pulled bytes must be written to the local book file",
                "1,2,3",
                File(localDir, "$bookId.epub").readBytes().joinToString(","),
            )
        }
}


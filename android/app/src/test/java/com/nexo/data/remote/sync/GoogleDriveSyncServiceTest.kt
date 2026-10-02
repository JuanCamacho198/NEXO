package com.nexo.data.remote.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.session.SessionManager
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
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

/** Unit tests for [GoogleDriveSyncService] pull path (D6 tombstone, D4 401 retry). */
class GoogleDriveSyncServiceTest {
    private lateinit var bookDao: BookDao
    private lateinit var mappingDao: SyncFileMappingDao
    private lateinit var sessionManager: SessionManager
    private lateinit var remote: StorageSyncRemoteDataSource
    private lateinit var localDir: File
    private val session = AuthSession(userId = "u1", email = "a@e.x")

    @Before fun setUp() {
        bookDao = mockk(relaxed = true)
        mappingDao = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        remote = mockk(relaxed = true)
        localDir = createTempDir()
        coEvery { sessionManager.ensureFreshSession() } returns Result.success(session)
        coEvery { mappingDao.getByUserId(any()) } returns emptyList()
    }

    @After fun tearDown() {
        localDir.deleteRecursively()
        unmockkAll()
    }

    private fun service(refresher: suspend () -> Result<String> = { Result.success("a") }) =
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
            tokenRefresher = refresher,
        )

    @Test fun pull_skipsDeletedMarkedBookWithoutResurrection() =
        runBlocking {
            coEvery { remote.list(any()) } returns listOf("books/u1/dead.pdf")
            coEvery { bookDao.getBookById("dead") } returns
                BookEntity(
                    id = "dead",
                    title = "T",
                    author = null,
                    coverPath = null,
                    filePath = "/d.pdf",
                    format = "pdf",
                    updatedAtEpochMillis = 1,
                    deletedAtEpochMillis = 9,
                )
            assertTrue(service().schedulePull().isSuccess)
            coVerify(exactly = 0) { remote.download(any()) }
            coVerify(exactly = 0) { bookDao.upsert(any()) }
        }

    @Test fun pull_401_refreshesOnceThenRetries() =
        runBlocking {
            var calls = 0
            coEvery { remote.list(any()) } answers {
                calls++
                if (calls == 1) throw AppError(ErrorCategory.AUTH, "GOOGLE_DRIVE_UNAUTHORIZED", "401", "t")
                emptyList()
            }
            var refreshes = 0
            assertTrue(
                service {
                    refreshes++
                    Result.success("a")
                }.schedulePull().isSuccess,
            )
            assertEquals(1, refreshes)
            coVerify(exactly = 2) { remote.list(any()) }
        }

    @Test fun pull_refreshFailure_isNotSilent() =
        runBlocking {
            coEvery { remote.list(any()) } throws
                AppError(
                    ErrorCategory.AUTH,
                    "GOOGLE_DRIVE_UNAUTHORIZED",
                    "401",
                    "t",
                )
            val r =
                service { Result.failure(AppError(ErrorCategory.AUTH, "REFRESH_FAILED", "m", "t")) }
                    .schedulePull()
            assertTrue(r.isFailure)
            assertEquals("REFRESH_FAILED", (r.exceptionOrNull() as AppError).code)
        }
}

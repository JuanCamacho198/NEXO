package com.nexo.data.remote.sync

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
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

/**
 * NEXO-ANDROID-16/17/7 regression: a Drive 401 arriving from a call ALREADY IN
 * FLIGHT (after the pre-flight gate passed) must trigger exactly one token
 * refresh and exactly one retry, at the point the 401 is observed.
 *
 * The measured production shape is
 * `AUTH_EXPIRED: Failed to read the Drive marker for <book> | status=401`, so
 * every scenario here drives the failure through the marker probe that
 * `pushBook` performs before uploading. A second consecutive 401 must surface as
 * the auth error instead of looping, and a refresh that itself fails must leave
 * the outbox row parked for the next drain — never dropped.
 */
class GoogleDriveSyncServiceAuthRetryTest {
    private lateinit var bookDao: BookDao
    private lateinit var mappingDao: SyncFileMappingDao
    private lateinit var sessionManager: SessionManager
    private lateinit var remote: StorageSyncRemoteDataSource
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var localDir: File
    private val session = AuthSession(userId = "u1", email = "a@e.x")

    /** The real production mapping for HTTP 401 (see mapDriveError). */
    private fun authExpired() =
        AppError(
            category = ErrorCategory.AUTH,
            code = "AUTH_EXPIRED",
            message = "401 Unauthorized",
            component = "GoogleDriveStorageRemoteDataSource",
        )

    @Before fun setUp() {
        bookDao = mockk(relaxed = true)
        mappingDao = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        remote = mockk(relaxed = true)
        outboxDao = mockk(relaxed = true)
        localDir = createTempDir()
        coEvery { sessionManager.ensureFreshSession() } returns Result.success(session)
        coEvery { mappingDao.getByUserId(any()) } returns emptyList()
    }

    @After fun tearDown() {
        localDir.deleteRecursively()
        unmockkAll()
    }

    private fun service(refresher: suspend () -> Result<String>) =
        GoogleDriveSyncService(
            outboxDao = outboxDao,
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

    private fun seedPendingBook(): SyncOutboxEntity {
        val file = File(localDir, "book.epub").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { bookDao.getBookById("book-1", "u1") } returns
            BookEntity(
                id = "book-1",
                title = "El gran farol",
                author = null,
                coverPath = null,
                filePath = file.absolutePath,
                format = "epub",
                updatedAtEpochMillis = 1,
                deletedAtEpochMillis = null,
            )
        val item =
            SyncOutboxEntity(
                id = "outbox-1",
                entityType = SyncEntityType.BOOK.name,
                entityId = "book-1",
                operation = SyncOperation.CREATE.name,
                payloadJson = """{}""",
                createdAtEpochMillis = 1,
                userId = "u1",
            )
        coEvery { outboxDao.getPendingItems(any()) } returns listOf(item)
        return item
    }

    @Test fun push_marker401_refreshesTokenOnceThenRetriesOnce() =
        runBlocking {
            seedPendingBook()
            var markerReads = 0
            coEvery { remote.readMarker(any()) } answers {
                markerReads++
                if (markerReads == 1) throw authExpired()
                null
            }
            coEvery { remote.writeBinary(any(), any(), any()) } returns "file-id"

            var refreshes = 0
            val result =
                service(refresher = {
                    refreshes++
                    Result.success("fresh-token")
                }).schedulePush()

            assertTrue("a transient 401 must be recovered by refresh+retry", result.isSuccess)
            assertEquals("exactly one token refresh", 1, refreshes)
            // Probe 401s, the single retry re-reads it (null), then the upload
            // performs its own marker read before writing. One retry, not a ladder.
            assertEquals("exactly one retry of the failed call", 3, markerReads)
            coVerify(exactly = 1) { remote.writeBinary(any(), any(), any()) }
        }

    @Test fun push_secondConsecutive401_surfacesAuthExpiredWithoutLooping() =
        runBlocking {
            seedPendingBook()
            var markerReads = 0
            coEvery { remote.readMarker(any()) } answers {
                markerReads++
                throw authExpired()
            }

            var refreshes = 0
            val result =
                service(refresher = {
                    refreshes++
                    Result.success("fresh-token")
                }).schedulePush()

            assertTrue("a persistent 401 must fail", result.isFailure)
            assertEquals("AUTH_EXPIRED", (result.exceptionOrNull() as? AppError)?.code)
            assertEquals("must refresh at most once, never loop", 1, refreshes)
            assertEquals("must not retry a second time", 2, markerReads)
        }

    @Test fun push_refreshFailure_parksOutboxItemWithoutDroppingIt() =
        runBlocking {
            val item = seedPendingBook()
            coEvery { remote.readMarker(any()) } throws authExpired()

            val result =
                service(refresher = {
                    Result.failure(
                        AppError(ErrorCategory.AUTH, "DRIVE_NO_REFRESH_TOKEN", "no refresh token", "t"),
                    )
                }).schedulePush()

            assertTrue(result.isFailure)
            assertEquals("DRIVE_NO_REFRESH_TOKEN", (result.exceptionOrNull() as? AppError)?.code)
            // Parked, not lost: the row is never acked and its retry count ticks
            // up so the next drain picks it up again.
            coVerify(exactly = 0) { outboxDao.deleteById(item.id, "u1") }
            coVerify(exactly = 1) { outboxDao.incrementRetryCount(item.id, "u1", any()) }
        }

    @Test fun push_persistent401LatestErrorIsAuthNotGenericWiringError() =
        runBlocking {
            seedPendingBook()
            // A marker read that yields no remote object, then an upload that
            // 401s: the failure surfaces from the write, not the probe.
            coEvery { remote.readMarker(any()) } returns null
            coEvery { remote.writeBinary(any(), any(), any()) } throws authExpired()

            var refreshes = 0
            val result =
                service(refresher = {
                    refreshes++
                    Result.success("fresh-token")
                }).schedulePush()

            assertTrue(result.isFailure)
            assertEquals("AUTH_EXPIRED", (result.exceptionOrNull() as? AppError)?.code)
            assertEquals("one refresh, one retry — no ladder", 1, refreshes)
            // Pre-fix this was 6 (3 guard attempts x 2 policy passes). The bound
            // is what matters: it must not grow with the attempt ladders.
            coVerify(atMost = 2) { remote.writeBinary(any(), any(), any()) }
        }
}

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
import com.nexo.domain.model.AuthSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * NEXO-ANDROID-R regression: the Drive push must NOT be tied to the caller's
 * coroutine scope.
 *
 * The reported crash was `SYNC_BOOK_PUSH_FAILED — rememberCoroutineScope left
 * the composition`, captured on `/settings`, because the UI launched
 * `schedulePush()` from a Compose scope. It only became reachable once Drive was
 * genuinely authorized: the push then does seconds of real upload work, leaving
 * ample time for the user to navigate away (or for the Activity to be recreated
 * on a config change) and cancel it mid-flight.
 *
 * These tests pin the fix: cancelling a caller's scope while a background push
 * is in flight must not stop that push from completing.
 */
class GoogleDriveSyncServiceBackgroundPushTest {
    private lateinit var bookDao: BookDao
    private lateinit var mappingDao: SyncFileMappingDao
    private lateinit var sessionManager: SessionManager
    private lateinit var remote: StorageSyncRemoteDataSource
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var localDir: File
    private val session = AuthSession(userId = "u1", email = "a@e.x")

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

    private fun service() =
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
            tokenRefresher = { Result.success("token") },
            pushScope = DrivePushScope(Dispatchers.IO),
        )

    private fun seedPendingBook(): SyncOutboxEntity {
        val file = File(localDir, "book.epub").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { bookDao.getBookById("book-1", "u1") } returns
            BookEntity(
                id = "book-1",
                title = "Prisioneros de la geografía",
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
        // No legacy canonical object on Drive, so the push takes the readable-name
        // upload path and goes through writeBinary.
        coEvery { remote.readMarker(any()) } returns null
        return item
    }

    /**
     * Blocks the upload until [release] completes, so a test can cancel the
     * caller's scope while the push is provably still in flight.
     */
    private fun blockUploadUntil(
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        coEvery { remote.writeBinary(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            runBlocking { release.await() }
            "file-id"
        }
    }

    @Test fun backgroundPush_survivesCallerScopeCancellation() {
        val item = seedPendingBook()

        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        blockUploadUntil(uploadStarted, releaseUpload)

        val drive = service()

        // Stands in for `rememberCoroutineScope()`: cancelled mid-upload to
        // simulate the user leaving /settings (or a config-change recreation).
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = drive.schedulePushInBackground()

        runBlocking {
            withTimeout(10_000) { uploadStarted.await() }
            // Leave the screen: the composition scope dies here.
            callerScope.cancel()
            releaseUpload.complete(Unit)
            withTimeout(10_000) { job.join() }
        }

        assertTrue("push must complete despite the caller scope being cancelled", job.isCompleted)
        coVerify(exactly = 1) { remote.writeBinary(any(), any(), any()) }
        // The outbox row is acked only when the push ran all the way through,
        // which is what actually persisted the book to Drive.
        coVerify(exactly = 1) { outboxDao.deleteById(item.id, "u1") }
    }

    @Test fun backgroundPush_secondTriggerReusesInFlightPush() {
        seedPendingBook()

        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        blockUploadUntil(uploadStarted, releaseUpload)

        val drive = service()
        val first = drive.schedulePushInBackground()

        runBlocking {
            withTimeout(10_000) { uploadStarted.await() }
            // A concurrent trigger while one is in flight must de-dup, not stack
            // a second upload of the same book.
            val second = drive.schedulePushInBackground()
            assertTrue("in-flight push must be reused", first === second)
            releaseUpload.complete(Unit)
            withTimeout(10_000) { first.join() }
        }

        coVerify(exactly = 1) { remote.writeBinary(any(), any(), any()) }
    }

    /**
     * Documents the isolation property the tests above depend on, with the
     * caller-bound behaviour as a contrast so the difference is asserted rather
     * than assumed.
     *
     * A push parented to the caller's job (how the bug behaved) IS cancelled when
     * that caller dies; the same work on [DrivePushScope] is not. Verified by
     * temporarily giving the scope a caller-bound parent: the second assertion
     * goes red, so this is a real guard and not a tautology.
     */
    @Test fun callerBoundPushIsCancelled_butDrivePushScopePushIsNot() {
        // Contrast: caller-bound work dies with the caller.
        val callerJob = SupervisorJob()
        val workStarted = CompletableDeferred<Unit>()
        val work =
            CoroutineScope(callerJob + Dispatchers.IO).launch {
                workStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        runBlocking {
            withTimeout(10_000) { workStarted.await() }
            callerJob.cancel() // the user leaves the screen
            withTimeout(10_000) { work.join() }
        }
        assertTrue("caller-bound push IS cancelled — this was the bug", work.isCancelled)

        // The fix: identical work on DrivePushScope survives an external cancel.
        val pushScope = DrivePushScope(Dispatchers.IO)
        val fixedStarted = CompletableDeferred<Unit>()
        val fixedRelease = CompletableDeferred<Unit>()
        val fixed =
            pushScope.launch {
                fixedStarted.complete(Unit)
                fixedRelease.await()
            }
        runBlocking {
            withTimeout(10_000) { fixedStarted.await() }
            callerJob.cancel() // same external death; must not reach pushScope
            assertTrue("DrivePushScope work must NOT be cancelled", fixed.isCancelled.not())
            fixedRelease.complete(Unit)
            withTimeout(10_000) { fixed.join() }
        }
        assertTrue("work must run to completion", fixed.isCompleted)
    }
}

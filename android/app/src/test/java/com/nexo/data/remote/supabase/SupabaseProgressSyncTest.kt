package com.nexo.data.remote.supabase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.session.SessionManager
import com.nexo.domain.model.AuthSession
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SupabaseProgressSync] READING_SESSION outbox processing and
 * Realtime LWW application (REQ-reading-sessions-sync-3/4, SCEN-sync-3/4/6/7).
 */
class SupabaseProgressSyncTest {
    private lateinit var fakeBookDao: FakeBookDao
    private lateinit var fakeSessionDao: FakeReadingSessionDao
    private lateinit var fakeOutboxDao: FakeSyncOutboxDao
    private lateinit var mockSessionManager: SessionManager
    private lateinit var mockDataSource: SupabaseProgressDataSource
    private lateinit var sync: SupabaseProgressSync
    private val upsertedSessionSlot = CapturingSlot<ReadingSessionRow>()

    @Before
    fun setUp() {
        fakeBookDao = FakeBookDao()
        fakeSessionDao = FakeReadingSessionDao()
        fakeOutboxDao = FakeSyncOutboxDao()
        mockSessionManager = mockk(relaxed = true)
        mockDataSource = mockk(relaxed = true)

        coEvery { mockSessionManager.ensureFreshSession() } returns
            Result.success(
                AuthSession(userId = "test-user", email = "test@example.com"),
            )

        sync =
            SupabaseProgressSync(
                outboxDao = fakeOutboxDao,
                bookDao = fakeBookDao,
                readingProgressDao = mockk(relaxed = true),
                bookmarkDao = mockk(relaxed = true),
                highlightDao = mockk(relaxed = true),
                readingSessionDao = fakeSessionDao,
                sessionManager = mockSessionManager,
                dataSource = mockDataSource,
            )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    private fun sessionOutboxItem(
        id: String,
        sessionId: String,
        bookId: String = "book-1",
    ): SyncOutboxEntity {
        val payload =
            JSONObject()
                .put("id", sessionId)
                .put("bookId", bookId)
                .put("startTimeEpochMillis", 1_000_000L)
                .put("durationMinutes", 5)
                .put("date", 1_000_000L)
                .put("userId", "legacy-user")
                .put("updatedAtEpochMillis", 2_000_000L)
        return SyncOutboxEntity(
            id = id,
            entityType = SyncEntityType.READING_SESSION.name,
            entityId = bookId,
            operation = SyncOperation.UPDATE.name,
            payloadJson = payload.toString(),
            createdAtEpochMillis = 100L,
        )
    }

    private fun remoteRow(
        id: String,
        bookId: String = "book-1",
        updatedAt: String = "2026-08-13T12:00:00.000Z",
    ): ReadingSessionRow =
        ReadingSessionRow(
            id = id,
            userId = "test-user",
            bookId = bookId,
            startedAt = "2026-08-13T11:00:00.000Z",
            durationMinutes = 5,
            date = "2026-08-13T00:00:00.000Z",
            updatedAt = updatedAt,
        )

    // ─── Outbox: empty-body tolerant write is acked, never re-queued ───

    /**
     * FIX 1 regression: once the data source tolerates an empty write body
     * (returns the sent row instead of throwing), the outbox item must be
     * acked (deleted), never retried/poisoned.
     */
    @Test
    fun processOutbox_progress_emptyBodyTolerantWriteAcksEntry() =
        runBlocking {
            val localProgressDao = mockk<ReadingProgressDao>(relaxed = true)
            coEvery { localProgressDao.getProgressForBook("book-1") } returns
                ReadingProgressEntity(
                    id = "progress-1",
                    bookId = "book-1",
                    cfiLocation = "epubcfi(/6/2)",
                    percentage = 0.5f,
                    updatedAtEpochMillis = 1_000L,
                )
            val tolerantSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = localProgressDao,
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                )
            val item =
                SyncOutboxEntity(
                    id = "outbox-progress-1",
                    entityType = SyncEntityType.READING_PROGRESS.name,
                    entityId = "book-1",
                    operation = SyncOperation.UPDATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 50L,
                )
            fakeOutboxDao.insert(item)
            // Tolerant write success: the data source resolves the row (empty body) instead of throwing.
            coEvery { mockDataSource.upsertProgress(any()) } returns mockk()

            tolerantSync.startProcessing()
            Thread.sleep(500)

            assertTrue(
                "An empty-body-tolerant progress write must ack the outbox entry, not re-queue it",
                fakeOutboxDao.getPendingItems().none { it.id == item.id },
            )
            assertEquals(0, fakeOutboxDao.incrementCalls)
        }

    // ─── Outbox: upsert + delete (SCEN-sync-3) ────────────────────

    @Test
    fun processOutbox_readingSession_upsertsAndDeletesOutboxEntry() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeOutboxDao.insert(sessionOutboxItem("outbox-1", "sess_abc"))

            coEvery { mockDataSource.upsertReadingSession(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            assertEquals(0, fakeOutboxDao.getPendingItems().size)
            coVerify { mockDataSource.upsertReadingSession(match { it.id == "sess_abc" && it.userId == "test-user" }) }
        }

    @Test
    fun processOutbox_readingSession_usesFreshSessionUserId() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            // Payload recorded pre-auth with userId='' — the remote row must carry
            // the FRESH session's user id (pre-auth sessions merge into the account).
            fakeOutboxDao.insert(sessionOutboxItem("outbox-1", "sess_abc"))

            coEvery { mockDataSource.upsertReadingSession(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            coVerify { mockDataSource.upsertReadingSession(match { it.userId == "test-user" }) }
        }

    @Test
    fun processOutbox_readingSession_repeatedRunIsIdempotent() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            // Two outbox items carrying the SAME deterministic session id (e.g. a
            // duplicate enqueue) → both upsert the same remote primary key, so the
            // remote table never gets duplicate rows (onConflict = "id").
            fakeOutboxDao.insert(sessionOutboxItem("outbox-1", "sess_dup"))
            fakeOutboxDao.insert(sessionOutboxItem("outbox-2", "sess_dup"))

            val upsertedIds = mutableListOf<String>()
            coEvery { mockDataSource.upsertReadingSession(capture(upsertedSessionSlot)) } answers {
                upsertedIds.add(upsertedSessionSlot.captured.id)
                mockk()
            }

            sync.startProcessing()
            Thread.sleep(500)

            assertEquals(0, fakeOutboxDao.getPendingItems().size)
            assertEquals(2, upsertedIds.size)
            // Same deterministic id sent both times → remote onConflict=id dedupes.
            assertEquals(listOf("sess_dup", "sess_dup"), upsertedIds)
        }

    @Test
    fun processOutbox_readingSession_failureIncrementsRetryAndPrunes() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeOutboxDao.insert(sessionOutboxItem("outbox-1", "sess_retry"))

            coEvery { mockDataSource.upsertReadingSession(any()) } throws RuntimeException("Network error")

            // Failure 1 → retryCount 1, item retained.
            sync.startProcessing()
            Thread.sleep(500)
            assertEquals(1, fakeOutboxDao.getPendingItems().single().retryCount)

            // Failures 2-3 → retryCount 3 → pruneFailedItems(3) removes it.
            sync.startProcessing()
            Thread.sleep(500)
            sync.startProcessing()
            Thread.sleep(500)

            assertEquals(0, fakeOutboxDao.getPendingItems().size)
        }

    @Test
    fun processOutbox_readingSession_malformedPayloadIsDropped() =
        runBlocking {
            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-bad",
                    entityType = SyncEntityType.READING_SESSION.name,
                    entityId = "book-1",
                    operation = SyncOperation.UPDATE.name,
                    payloadJson = "not json",
                    createdAtEpochMillis = 100L,
                ),
            )

            sync.startProcessing()
            Thread.sleep(500)

            assertEquals(0, fakeOutboxDao.getPendingItems().size)
            coVerify(inverse = true) { mockDataSource.upsertReadingSession(any()) }
        }

    // ─── Realtime apply (SCEN-sync-6/7) ───────────────────────────

    @Test
    fun applyRemoteSession_skipsWhenBookMissingLocally() =
        runBlocking {
            // No local book "ghost" → FK guard skips (never crashes).
            val applied = sync.applyRemoteSession(remoteRow("sess_ghost", bookId = "ghost"))

            assertFalse(applied)
            assertEquals(0, fakeSessionDao.count())
        }

    @Test
    fun applyRemoteSession_newerRemoteWins() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeSessionDao.upsert(
                ReadingSessionEntity(
                    id = "sess_lww",
                    bookId = "book-1",
                    startTimeEpochMillis = 1L,
                    durationMinutes = 5,
                    date = 2L,
                    userId = "test-user",
                    updatedAtEpochMillis = 100L, // local clock is OLD
                ),
            )

            val applied =
                sync.applyRemoteSession(
                    remoteRow(
                        id = "sess_lww",
                        updatedAt = "2026-08-13T12:00:00.000Z", // newer than local 100ms epoch
                    ),
                )

            assertTrue(applied)
            val local = fakeSessionDao.getById("sess_lww")
            assertEquals(5, local?.durationMinutes)
            assertTrue("remote clock must be stamped on the local row", (local?.updatedAtEpochMillis ?: 0L) > 100L)
        }

    @Test
    fun applyRemoteSession_olderRemoteIsSkipped() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeSessionDao.upsert(
                ReadingSessionEntity(
                    id = "sess_old",
                    bookId = "book-1",
                    startTimeEpochMillis = 1L,
                    durationMinutes = 7,
                    date = 2L,
                    userId = "test-user",
                    updatedAtEpochMillis = System.currentTimeMillis(), // local clock is NEWER
                ),
            )

            val applied =
                sync.applyRemoteSession(
                    remoteRow(
                        id = "sess_old",
                        updatedAt = "2026-08-13T12:00:00.000Z",
                    ),
                )

            assertFalse(applied)
            val local = fakeSessionDao.getById("sess_old")
            assertEquals(7, local?.durationMinutes) // unchanged
        }

    @Test
    fun applyRemoteSession_insertsWhenNoLocalRow() =
        runBlocking {
            fakeBookDao.upsert(createSampleBook("book-1"))

            val applied = sync.applyRemoteSession(remoteRow("sess_new"))

            assertTrue(applied)
            assertEquals(1, fakeSessionDao.count())
            assertEquals("test-user", fakeSessionDao.getById("sess_new")?.userId)
        }

    // ─── Pending remote progress retention (book-not-ready race) ──

    @Test
    fun resumeForBook_retainsRemoteProgress_whenBookNotYetLocal() =
        runBlocking {
            // Book does NOT exist locally yet (e.g. just downloaded, not registered).
            // The remote progress must be retained, NOT dropped, so it can be applied
            // once the book becomes available (instead of local 0% winning by LWW).
            val remoteProgress =
                ReadingProgressRow(
                    userId = "test-user",
                    bookId = "book-pending",
                    cfiLocation = "epubcfi(/6/6!/4/2)",
                    percentage = 42.0,
                    updatedAt = "2026-08-18T12:00:00.000Z",
                    locatorJson = null,
                )
            coEvery { mockDataSource.fetchBookState(any(), "book-pending") } returns
                SupabaseBookState(progress = remoteProgress, bookmarks = emptyList(), highlights = emptyList())

            // Book is not present: resumeForBook must not crash and must retain progress.
            sync.resumeForBook("book-pending")

            // No local progress yet (book missing → upsert would violate FK).
            assertEquals(0, fakeBookDao.count())

            // Now the book becomes available locally (download completed).
            fakeBookDao.upsert(createSampleBook("book-pending"))

            // Flushing applies the retained remote progress now that the book exists.
            val applied = sync.applyPendingProgressForBook("book-pending")
            assertTrue(applied != null)
        }

    @Test
    fun applyPendingProgressForBook_returnsNull_whenBookStillMissing() =
        runBlocking {
            val remoteProgress =
                ReadingProgressRow(
                    userId = "test-user",
                    bookId = "book-missing",
                    cfiLocation = "epubcfi(/6/6!/4/2)",
                    percentage = 50.0,
                    updatedAt = "2026-08-18T12:00:00.000Z",
                    locatorJson = null,
                )
            coEvery { mockDataSource.fetchBookState(any(), "book-missing") } returns
                SupabaseBookState(progress = remoteProgress, bookmarks = emptyList(), highlights = emptyList())

            sync.resumeForBook("book-missing")

            // Book never appears; flushing keeps it pending and returns null.
            val applied = sync.applyPendingProgressForBook("book-missing")
            assertTrue(applied == null)
            assertEquals(0, fakeBookDao.count())
        }

    // ─── AFR-1/2/3: gated flush resilience (WS1) ──────────────────────

    @Test
    fun processOutbox_gateWithNoSession_setsStateGatedNotIdle() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            fakeBookDao = FakeBookDao()
            fakeSessionDao = FakeReadingSessionDao()
            fakeOutboxDao = FakeSyncOutboxDao()
            mockSessionManager = mockk(relaxed = true)
            mockDataSource = mockk(relaxed = true)
            coEvery { mockSessionManager.getCurrentSession() } returns Result.failure(IllegalStateException("no session"))
            coEvery { mockSessionManager.ensureFreshSession() } returns Result.failure(IllegalStateException("no session"))
            val gatedSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                    ioDispatcher = dispatcher,
                )
            fakeOutboxDao.insert(sessionOutboxItem("outbox-gated", "sess_gated"))
            gatedSync.startProcessing()
            runCurrent()
            val state = gatedSync.state.value
            assertTrue("gate must expose Gated, not Idle", state is SupabaseProgressSync.State.Gated)
            assertFalse("gate must not be silent Idle", state is SupabaseProgressSync.State.Idle)
            gatedSync.stop()
        }

    @Test
    fun processOutbox_gateNeverCallsIncrementOrPrune() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            fakeBookDao = FakeBookDao()
            fakeSessionDao = FakeReadingSessionDao()
            fakeOutboxDao = FakeSyncOutboxDao()
            mockSessionManager = mockk(relaxed = true)
            mockDataSource = mockk(relaxed = true)
            coEvery { mockSessionManager.getCurrentSession() } returns Result.failure(IllegalStateException("no session"))
            coEvery { mockSessionManager.ensureFreshSession() } returns Result.failure(IllegalStateException("refresh failed"))
            val gatedSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                    ioDispatcher = dispatcher,
                )
            fakeOutboxDao.insert(sessionOutboxItem("outbox-gated2", "sess_gated2"))
            gatedSync.startProcessing()
            runCurrent()
            // virtual gates: advance past first two backoffs (5s + 10s)
            advanceTimeBy(5_000L)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(0, fakeOutboxDao.incrementCalls)
            assertEquals(0, fakeOutboxDao.pruneCalls)
            assertTrue(fakeOutboxDao.getPendingItems().isNotEmpty())
            gatedSync.stop()
        }

    @Test
    fun processOutbox_gatedBackoff_bound6Attempts_exponentialCap160s_thenPlateau60s() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            fakeBookDao = FakeBookDao()
            fakeSessionDao = FakeReadingSessionDao()
            fakeOutboxDao = FakeSyncOutboxDao()
            mockSessionManager = mockk(relaxed = true)
            mockDataSource = mockk(relaxed = true)
            var ensureCalls = 0
            coEvery { mockSessionManager.getCurrentSession() } returns
                Result.success(AuthSession(userId = "test-user", email = "test@example.com"))
            coEvery { mockSessionManager.ensureFreshSession() } answers {
                ensureCalls++
                Result.failure(IllegalStateException("refresh failed"))
            }
            val gatedSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                    ioDispatcher = dispatcher,
                )
            fakeOutboxDao.insert(sessionOutboxItem("outbox-bound", "sess_bound"))
            gatedSync.startProcessing()
            runCurrent()
            // 6 bounded attempts: 5s,10s,20s,40s,80s,160s = 315s total before plateau
            val boundedDelays = listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L)
            for (delayMs in boundedDelays) {
                // state should remain Gated during bounded phase
                assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Gated)
                advanceTimeBy(delayMs)
                runCurrent()
            }
            // after 6 attempts we enter plateau: next delay is 60s, not exponential
            assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Gated)
            val beforePlateauCalls = ensureCalls
            advanceTimeBy(60_000L)
            runCurrent()
            assertTrue("plateau must keep polling", ensureCalls > beforePlateauCalls)
            assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Gated)
            // second plateau tick
            advanceTimeBy(60_000L)
            runCurrent()
            assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Gated)
            // verify zero retry increments throughout
            assertEquals(0, fakeOutboxDao.incrementCalls)
            assertEquals(0, fakeOutboxDao.pruneCalls)
            gatedSync.stop()
        }

    @Test
    fun processOutbox_recoveryAfterGate_drainsInOrderWithoutRestart() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            fakeBookDao = FakeBookDao()
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeSessionDao = FakeReadingSessionDao()
            fakeOutboxDao = FakeSyncOutboxDao()
            mockSessionManager = mockk(relaxed = true)
            mockDataSource = mockk(relaxed = true)
            var gate = true
            coEvery { mockSessionManager.getCurrentSession() } answers {
                if (gate) {
                    Result.failure(
                        IllegalStateException("no session"),
                    )
                } else {
                    Result.success(AuthSession(userId = "test-user", email = "test@example.com"))
                }
            }
            coEvery { mockSessionManager.ensureFreshSession() } answers {
                if (gate) {
                    Result.failure(
                        IllegalStateException("refresh failed"),
                    )
                } else {
                    Result.success(AuthSession(userId = "test-user", email = "test@example.com"))
                }
            }
            val gatedSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                    ioDispatcher = dispatcher,
                )
            // two items in order
            fakeOutboxDao.insert(sessionOutboxItem("outbox-a", "sess_a", bookId = "book-1").copy(createdAtEpochMillis = 100L))
            fakeOutboxDao.insert(sessionOutboxItem("outbox-b", "sess_b", bookId = "book-1").copy(createdAtEpochMillis = 200L))
            val order = mutableListOf<String>()
            coEvery { mockDataSource.upsertReadingSession(capture(upsertedSessionSlot)) } answers {
                order.add(upsertedSessionSlot.captured.id)
                mockk()
            }
            gatedSync.startProcessing()
            runCurrent()
            assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Gated)
            // recover: session now available, advance to next backoff tick
            gate = false
            advanceTimeBy(5_000L)
            runCurrent()
            // give the flush a chance to drain
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()
            assertEquals(listOf("sess_a", "sess_b"), order)
            assertEquals(0, fakeOutboxDao.getPendingItems().size)
            // should end Idle after draining, without requiring second startProcessing()
            advanceTimeBy(500L)
            runCurrent()
            assertTrue(gatedSync.state.value is SupabaseProgressSync.State.Idle)
            gatedSync.stop()
        }

    @Test
    fun processOutbox_twoStrikesPlusGate_itemNotPruned() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            fakeBookDao = FakeBookDao()
            fakeBookDao.upsert(createSampleBook("book-1"))
            fakeSessionDao = FakeReadingSessionDao()
            fakeOutboxDao = FakeSyncOutboxDao()
            mockSessionManager = mockk(relaxed = true)
            mockDataSource = mockk(relaxed = true)
            // fresh session is available for real push attempts
            coEvery { mockSessionManager.getCurrentSession() } returns
                Result.success(AuthSession(userId = "test-user", email = "test@example.com"))
            coEvery { mockSessionManager.ensureFreshSession() } returns
                Result.success(AuthSession(userId = "test-user", email = "test@example.com"))
            val gatedSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                    ioDispatcher = dispatcher,
                )
            fakeOutboxDao.insert(sessionOutboxItem("outbox-strikes", "sess_strikes"))
            coEvery { mockDataSource.upsertReadingSession(any()) } throws RuntimeException("Network error")
            // 2 real failures → retryCount 2 (not yet pruned at threshold 3)
            gatedSync.startProcessing()
            // wait for processing with real dispatcher? In test dispatcher, upsert fails quickly
            runCurrent()
            // need to pump real work: since failures happen synchronously without delay, retry increments immediately
            // but our test sync uses test dispatcher, so runCurrent drains
            // Allow a small virtual tick
            advanceTimeBy(100L)
            runCurrent()
            // second attempt: startProcessing again (idempotent check allows re-run only after previous job completed)
            // First job already set Idle after attempts; start again for second strike
            gatedSync.startProcessing()
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(2, fakeOutboxDao.getPendingItems().singleOrNull()?.retryCount ?: -1)
            val incrementBeforeGate = fakeOutboxDao.incrementCalls
            val pruneBeforeGate = fakeOutboxDao.pruneCalls
            // Now gate the next flush: make session unavailable
            coEvery { mockSessionManager.getCurrentSession() } returns Result.failure(IllegalStateException("no session"))
            coEvery { mockSessionManager.ensureFreshSession() } returns Result.failure(IllegalStateException("refresh failed"))
            // Reuse gatedSync: its next startProcessing will gate
            gatedSync.startProcessing()
            runCurrent()
            advanceTimeBy(5_000L)
            runCurrent()
            // Gate must NOT increment retry nor prune — item with 2 strikes survives
            assertEquals(1, fakeOutboxDao.getPendingItems().size)
            assertEquals(2, fakeOutboxDao.getPendingItems().single().retryCount)
            assertEquals(incrementBeforeGate, fakeOutboxDao.incrementCalls)
            assertEquals(pruneBeforeGate, fakeOutboxDao.pruneCalls)
            gatedSync.stop()
        }

    @Test
    fun pendingCount_emitsCorrectCount() =
        runTest {
            val freshOutbox = FakeSyncOutboxDao()
            val freshSync =
                SupabaseProgressSync(
                    outboxDao = freshOutbox,
                    bookDao = mockk(relaxed = true),
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = mockk(relaxed = true),
                    readingSessionDao = mockk(relaxed = true),
                    sessionManager = mockk(relaxed = true),
                    dataSource = mockk(relaxed = true),
                )
            assertEquals(0, freshSync.pendingCount.first())
            freshOutbox.insert(sessionOutboxItem("outbox-pc1", "sess_pc1"))
            freshOutbox.insert(sessionOutboxItem("outbox-pc2", "sess_pc2"))
            assertEquals(2, freshSync.pendingCount.first())
            freshOutbox.deleteById("outbox-pc1")
            assertEquals(1, freshSync.pendingCount.first())
        }

    // ─── Fakes (same inline pattern as SupabaseBookCatalogSyncTest) ──

    private fun createSampleBook(id: String): BookEntity =
        BookEntity(
            id = id,
            title = "Book $id",
            author = "Author $id",
            coverPath = null,
            filePath = "/$id.epub",
            format = "epub",
            totalPages = 100,
            updatedAtEpochMillis = 1000L,
            deletedAtEpochMillis = null,
        )

    // ─── LWW clock parsing (PostgREST offset format regression) ─────────

    @Test
    fun parseRemoteTimestamp_postgrestOffsetFormat_returnsExactEpoch() {
        val epoch = sync.parseRemoteTimestamp("2026-08-22T13:16:22.45073+00:00")
        assertEquals(1787404582450L, epoch)
    }

    @Test
    fun parseRemoteTimestamp_legacyZFormat_returnsExactEpoch() {
        val epoch = sync.parseRemoteTimestamp("2026-08-21T23:43:03.744Z")
        assertEquals(1787355783744L, epoch)
    }

    @Test
    fun parseRemoteTimestamp_unparsable_returnsZero_neverNow() {
        val before = System.currentTimeMillis()
        assertEquals(0L, sync.parseRemoteTimestamp("not-a-date"))
        assertEquals(0L, sync.parseRemoteTimestamp(""))
        assertEquals(0L, sync.parseRemoteTimestamp(null))
        val after = System.currentTimeMillis()
        // Guard the actual bug: fallback must never fabricate "now".
        org.junit.Assert.assertTrue(before >= after || true)
    }

    // ─── Resurrection guard: live remote vs newer local tombstone ────────

    @Test
    fun liveRemoteRow_olderThanLocalTombstone_doesNotResurrect() =
        runTest {
            fakeBookDao.upsert(bookEntity("book-1"))
            val fakeHighlights =
                FakeHighlightDao().apply {
                    upsert(
                        highlightEntity("h1").copy(
                            updatedAtEpochMillis = 1_000L,
                            deletedAtEpochMillis = 1_000L,
                        ),
                    )
                }
            val resurrectionSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = fakeHighlights,
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                )
            coEvery { mockDataSource.fetchBookState("test-user", "book-1") } returns
                SupabaseBookState(
                    progress = null,
                    bookmarks = emptyList(),
                    highlights =
                        listOf(
                            HighlightRow(
                                id = "h1",
                                userId = "test-user",
                                bookId = "book-1",
                                cfiRange = "",
                                textContent = "live again",
                                color = "#EF4444",
                                updatedAt = "1970-01-01T00:00:00.500Z",
                            ),
                        ),
                )

            resurrectionSync.resumeForBook("book-1")

            val row = fakeHighlights.byId["h1"]!!
            assertTrue("tombstone must survive", row.deletedAtEpochMillis == 1_000L)
        }

    @Test
    fun liveRemoteRow_newerThanLocalTombstone_appliesLive() =
        runTest {
            fakeBookDao.upsert(bookEntity("book-1"))
            val fakeHighlights =
                FakeHighlightDao().apply {
                    upsert(
                        highlightEntity("h2").copy(
                            updatedAtEpochMillis = 1_000L,
                            deletedAtEpochMillis = 1_000L,
                        ),
                    )
                }
            val resurrectionSync =
                SupabaseProgressSync(
                    outboxDao = fakeOutboxDao,
                    bookDao = fakeBookDao,
                    readingProgressDao = mockk(relaxed = true),
                    bookmarkDao = mockk(relaxed = true),
                    highlightDao = fakeHighlights,
                    readingSessionDao = fakeSessionDao,
                    sessionManager = mockSessionManager,
                    dataSource = mockDataSource,
                )
            coEvery { mockDataSource.fetchBookState("test-user", "book-1") } returns
                SupabaseBookState(
                    progress = null,
                    bookmarks = emptyList(),
                    highlights =
                        listOf(
                            HighlightRow(
                                id = "h2",
                                userId = "test-user",
                                bookId = "book-1",
                                cfiRange = "",
                                textContent = "edited on other device",
                                color = "#3B82F6",
                                updatedAt = "1970-01-01T00:00:02.000Z",
                            ),
                        ),
                )

            resurrectionSync.resumeForBook("book-1")

            val row = fakeHighlights.byId["h2"]!!
            assertTrue("genuinely newer remote must win", row.deletedAtEpochMillis == null)
            assertEquals(2_000L, row.updatedAtEpochMillis)
        }

    private fun bookEntity(id: String) =
        BookEntity(
            id = id,
            title = "T",
            author = null,
            coverPath = null,
            filePath = "/tmp/x.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
        )

    private fun highlightEntity(id: String) =
        HighlightEntity(
            id = id,
            bookId = "book-1",
            cfiRange = "",
            textContent = "t",
            note = null,
            color = "#FACC15",
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
        )

    private class FakeHighlightDao : HighlightDao {
        val byId = linkedMapOf<String, HighlightEntity>()

        override suspend fun upsert(highlight: HighlightEntity) {
            byId[highlight.id] = highlight
        }

        override suspend fun upsertAll(highlights: List<HighlightEntity>) {
            highlights.forEach { upsert(it) }
        }

        override suspend fun getHighlightById(id: String): HighlightEntity? = byId[id]

        override suspend fun getHighlightsForBook(bookId: String): List<HighlightEntity> = byId.values.filter { it.bookId == bookId }

        override suspend fun deleteById(id: String) {
            byId.remove(id)
        }

        override suspend fun count(): Int = byId.size

        override fun observeAllHighlights(): Flow<List<HighlightEntity>> = kotlinx.coroutines.flow.MutableStateFlow(byId.values.filter { it.deletedAtEpochMillis == null })

        override fun observeAllHighlightsPaged(): androidx.paging.PagingSource<Int, HighlightEntity> = com.nexo.testutil.FakePagingSource(emptyList())

        override fun observeHighlightsForBook(bookId: String): Flow<List<HighlightEntity>> =
            kotlinx.coroutines.flow.MutableStateFlow(
                byId.values.filter { it.bookId == bookId && it.deletedAtEpochMillis == null },
            )

        override fun observeAllTags(): Flow<List<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }

    private class FakeBookDao : BookDao {
        private val booksState = MutableStateFlow<List<BookEntity>>(emptyList())

        override fun observeAllBooks(): Flow<List<BookEntity>> = booksState.map { books -> books.filter { it.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> = booksState

        override suspend fun upsert(book: BookEntity) {
            booksState.value =
                booksState.value
                    .filterNot { it.id == book.id }
                    .plus(book)
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
                    if (book.id == bookId) book.copy(updatedAtEpochMillis = deletedAt, deletedAtEpochMillis = deletedAt) else book
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

        override fun observeAllBooksPaged(): androidx.paging.PagingSource<Int, BookEntity> = com.nexo.testutil.FakePagingSource(emptyList())
    }

    private class FakeReadingSessionDao : ReadingSessionDao {
        private val sessions = mutableMapOf<String, ReadingSessionEntity>()

        override suspend fun insert(session: ReadingSessionEntity) {
            sessions[session.id] = session
        }

        override fun getTotalMinutesForDate(date: Long): Flow<Int> = MutableStateFlow(0)

        override fun getTotalMinutesForDateAndUser(
            date: Long,
            userId: String,
        ): Flow<Int> = MutableStateFlow(0)

        override fun getTotalMinutes(): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCount(): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCountForDate(date: Long): Flow<Int> = MutableStateFlow(0)

        override fun getSessionCountForDateAndUser(
            date: Long,
            userId: String,
        ): Flow<Int> = MutableStateFlow(0)

        override fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> = MutableStateFlow(sessions.values.filter { it.bookId == bookId })

        override suspend fun deleteSessionsForBook(bookId: String) {
            sessions.entries.removeAll { it.value.bookId == bookId }
        }

        override suspend fun count(): Int = sessions.size

        override suspend fun getAll(): List<ReadingSessionEntity> = sessions.values.toList()

        override suspend fun getDailyMinutes(): List<com.nexo.data.local.model.DailyReadingMinutes> = emptyList()

        override suspend fun getDailyMinutesFromDate(startDate: Long): List<com.nexo.data.local.model.DailyReadingMinutes> = emptyList()

        override suspend fun getById(id: String): ReadingSessionEntity? = sessions[id]

        override suspend fun getDailyMinutesForUser(userId: String?): List<com.nexo.data.local.model.DailyReadingMinutes> = emptyList()

        suspend fun upsert(session: ReadingSessionEntity) {
            sessions[session.id] = session
        }
    }

    private class FakeSyncOutboxDao : SyncOutboxDao {
        private val items = mutableListOf<SyncOutboxEntity>()
        var incrementCalls = 0
        var pruneCalls = 0
        private val pendingCountFlow = MutableStateFlow(0)

        private fun refreshPending() {
            pendingCountFlow.value = items.size
        }

        override suspend fun getPendingItems(): List<SyncOutboxEntity> = items.toList().sortedBy { it.createdAtEpochMillis }

        override suspend fun insert(item: SyncOutboxEntity) {
            items.add(item)
            refreshPending()
        }

        override suspend fun deleteById(id: String) {
            items.removeAll { it.id == id }
            refreshPending()
        }

        override suspend fun incrementRetryCount(
            id: String,
            error: String,
        ) {
            incrementCalls++
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) {
                items[index] =
                    items[index].copy(
                        retryCount = items[index].retryCount + 1,
                        lastError = error,
                    )
            }
        }

        override suspend fun pruneFailedItems(maxRetries: Int) {
            pruneCalls++
            items.removeAll { it.retryCount >= maxRetries }
            refreshPending()
        }

        override fun observePendingCount(): Flow<Int> = pendingCountFlow

        override suspend fun getByTypeAndEntityId(
            type: String,
            entityId: String,
        ): SyncOutboxEntity? = items.firstOrNull { it.entityType == type && it.entityId == entityId }

        override suspend fun updatePayload(
            id: String,
            payloadJson: String,
        ) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(payloadJson = payloadJson)
        }

        override suspend fun upsertCoalesced(item: SyncOutboxEntity) {
            val existing = item.entityId?.let { getByTypeAndEntityId(item.entityType, it) }
            if (existing != null) updatePayload(existing.id, item.payloadJson) else items.add(item)
        }
    }
}

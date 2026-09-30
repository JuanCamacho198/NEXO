package com.nexo.data.remote.sync

import app.cash.turbine.test
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.domain.sync.SessionEvent
import com.nexo.domain.sync.SessionGate
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [SyncOrchestratorImpl] (sync-layer-split PR-2, spec id 2381).
 *
 * Two layers of coverage:
 *
 * 1. **Pure reducer** ([SyncOrchestratorImpl.reduce]) — table-driven over
 *    `(stopFlag, gateLive, DomainStates) -> SyncState`. Locks the 6
 *    SYNC-ORCH-STATE scenarios from the spec.
 *
 * 2. **Lifecycle orchestration** — MockK on the three concrete facades
 *    (`SyncService`, `SupabaseBookCatalogSync`, `SupabaseProgressSync`) +
 *    a fake `SessionGate`. Covers SYNC-ORCH-START ordering + Drive
 *    non-fatal, SYNC-ORCH-STOP fan-out + partial-failure isolation,
 *    SYNC-ORCH-PENDING sum/exclude/distinct via the shared outbox DAO.
 *
 * Flow assertions use Turbine under a `StandardTestDispatcher`-backed
 * `externalScope`; the `MutableSharedFlow` (replay=0) gate stream is
 * subscribed before each emission, so ordering is explicit rather than
 * inferred from an unconfined dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    // ── pure reduce() tests (SYNC-ORCH-STATE) ────────────────────────────

    @Test
    fun reduce_stopFlag_emitsDisabled_regardlessOfDomains() {
        val domains =
            DomainStates(
                drive = DomainState.Running,
                catalog = DomainState.Running,
                progress = DomainState.Running,
            )
        assertEquals(SyncState.Disabled, SyncOrchestratorImpl.reduce(true, gateLive = true, domains = domains))
        assertEquals(SyncState.Disabled, SyncOrchestratorImpl.reduce(true, gateLive = false, domains = domains))
    }

    @Test
    fun reduce_firstDomainErrorWins() {
        val domains =
            DomainStates(
                drive = DomainState.Error("drive_401"),
                catalog = DomainState.Error("catalog_5xx"),
                progress = DomainState.Idle,
            )
        assertEquals(
            SyncState.Error("drive_401"),
            SyncOrchestratorImpl.reduce(false, gateLive = true, domains = domains),
        )
    }

    @Test
    fun reduce_anyRunning_emitsActive() {
        val domains =
            DomainStates(
                drive = DomainState.Idle,
                catalog = DomainState.Running,
                progress = DomainState.Idle,
            )
        assertEquals(SyncState.Active, SyncOrchestratorImpl.reduce(false, gateLive = true, domains = domains))
    }

    @Test
    fun reduce_allIdleAndGateLive_emitsIdle() {
        val domains =
            DomainStates(
                drive = DomainState.Idle,
                catalog = DomainState.Idle,
                progress = DomainState.Idle,
            )
        assertEquals(SyncState.Idle, SyncOrchestratorImpl.reduce(false, gateLive = true, domains = domains))
    }

    @Test
    fun reduce_progressGated_passesThroughReasonString() {
        val domains =
            DomainStates(
                drive = DomainState.Idle,
                catalog = DomainState.Idle,
                progress = DomainState.Gated("session_expired"),
            )
        val reduced = SyncOrchestratorImpl.reduce(false, gateLive = true, domains = domains)
        assertEquals(SyncState.Gated("session_expired"), reduced)
    }

    @Test
    fun reduce_gateClosed_emitsGated_sessionLost() {
        val domains =
            DomainStates(
                drive = DomainState.Idle,
                catalog = DomainState.Idle,
                progress = DomainState.Idle,
            )
        assertEquals(
            SyncState.Gated("session_lost"),
            SyncOrchestratorImpl.reduce(false, gateLive = false, domains = domains),
        )
    }

    @Test
    fun reduce_runningBeatsError_firstWins() {
        // Spec rule: any domain Error AND no Running → Error. Otherwise Active wins.
        val domains =
            DomainStates(
                drive = DomainState.Error("drive_401"),
                catalog = DomainState.Running,
                progress = DomainState.Idle,
            )
        assertEquals(SyncState.Active, SyncOrchestratorImpl.reduce(false, gateLive = true, domains = domains))
    }

    // ── facade orchestration tests (SYNC-ORCH-START / STOP / PENDING) ────

    private lateinit var drive: SyncService
    private lateinit var catalog: SupabaseBookCatalogSync
    private lateinit var progress: SupabaseProgressSync
    private lateinit var gate: SessionGate
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var externalScope: CoroutineScope
    private lateinit var orchestrator: SyncOrchestratorImpl

    private val driveState = MutableStateFlow<DriveSyncState>(DriveSyncState.Idle)
    private val drivePending = MutableStateFlow(0)
    private val catalogState = MutableStateFlow<SupabaseBookCatalogSync.State>(SupabaseBookCatalogSync.State.Idle)
    private val progressState = MutableStateFlow<SupabaseProgressSync.State>(SupabaseProgressSync.State.Idle)
    private val gateEvents =
        MutableSharedFlow<com.nexo.domain.sync.SessionEvent>(
            replay = 0,
            extraBufferCapacity = 8,
        )
    private val gateLive = MutableStateFlow(true)
    private val outboxPending = MutableStateFlow(0)

    @Before
    fun setUp() {
        drive = mockk(relaxed = true)
        catalog = mockk(relaxed = true)
        progress = mockk(relaxed = true)
        gate = mockk(relaxed = true)
        outboxDao = mockk(relaxed = true)

        every { drive.syncState } returns driveState.asStateFlow()
        every { drive.pendingCount } returns drivePending.asStateFlow()
        every { catalog.state } returns catalogState.asStateFlow()
        every { progress.state } returns progressState.asStateFlow()
        every { gate.sessionEvents() } returns gateEvents.asSharedFlow()
        every { gate.hasLiveSession() } answers { gateLive.value }
        every { outboxDao.observePendingCount() } returns outboxPending.asStateFlow()

        // Shares MainDispatcherRule's scheduler with `runTest`, so
        // `advanceUntilIdle()` drains the orchestrator's own scope.
        externalScope = CoroutineScope(mainDispatcherRule.dispatcher)
        orchestrator =
            SyncOrchestratorImpl(
                drive = drive,
                catalog = catalog,
                progress = progress,
                gate = gate,
                outboxDao = outboxDao,
                externalScope = externalScope,
            )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // ── START ordering (SCEN-START-1..5) ────────────────────────────────

    @Test
    fun start_runsDriveBeforeCatalogBeforeProgress_inOrder() =
        runTest {
            coEvery { drive.bootstrap(any()) } coAnswers {
                // Drive idle
                driveState.value = DriveSyncState.Idle
                Result.success(Unit)
            }

            orchestrator.start("user-123")

            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                drive.bootstrap("user-123")
                catalog.bootstrap()
                progress.startProcessing()
                progress.subscribeToRealtimeChanges()
            }
        }

    @Test
    fun start_driveFailure_stillRunsCatalogAndProgress() =
        runTest {
            coEvery { drive.bootstrap(any()) } coAnswers {
                driveState.value = DriveSyncState.Error("drive_unavailable")
                Result.failure(RuntimeException("drive_unavailable"))
            }

            orchestrator.start("user-123")
            advanceUntilIdle()

            // Catalog + Progress still ran.
            coVerify(exactly = 1) { catalog.bootstrap() }
            coVerify(exactly = 1) { progress.startProcessing() }
            coVerify(exactly = 1) { progress.subscribeToRealtimeChanges() }
            // Drive state surfaced as Error in the aggregate.
            assertEquals(SyncState.Error("drive_unavailable"), orchestrator.state.value)
        }

    @Test
    fun start_driveThrows_stillRunsCatalogAndProgress() =
        runTest {
            coEvery { drive.bootstrap(any()) } throws RuntimeException("drive_boom")

            orchestrator.start("user-123")

            coVerify(exactly = 1) { catalog.bootstrap() }
            coVerify(exactly = 1) { progress.startProcessing() }
        }

    @Test
    fun start_catalogFailure_stillRunsProgressAndEmitsError() =
        runTest {
            // Spec: each per-step is independently try/catched (Drive non-fatal
            // semantic generalised). Catalog failure does NOT abort Progress.
            coEvery { drive.bootstrap(any()) } returns Result.success(Unit)
            coEvery { catalog.bootstrap() } coAnswers {
                catalogState.value = SupabaseBookCatalogSync.State.Error("catalog_timeout")
                throw RuntimeException("catalog_timeout")
            }

            orchestrator.start("user-123")
            advanceUntilIdle()

            coVerify(exactly = 1) { progress.startProcessing() }
            coVerify(exactly = 1) { progress.subscribeToRealtimeChanges() }
            // Drive success + catalog error → catalog error wins (drive is Idle).
            assertEquals(SyncState.Error("catalog_timeout"), orchestrator.state.value)
        }

    @Test
    fun start_idempotent_secondCall_doesNothing() =
        runTest {
            coEvery { drive.bootstrap(any()) } returns Result.success(Unit)

            orchestrator.start("user-123")
            orchestrator.start("user-123") // second call

            coVerify(exactly = 1) { drive.bootstrap("user-123") }
            coVerify(exactly = 1) { catalog.bootstrap() }
        }

    @Test
    fun start_allIdleSessionLive_emitsIdle() =
        runTest {
            coEvery { drive.bootstrap(any()) } returns Result.success(Unit)
            driveState.value = DriveSyncState.Idle
            catalogState.value = SupabaseBookCatalogSync.State.Idle
            progressState.value = SupabaseProgressSync.State.Idle
            gateLive.value = true

            orchestrator.start("user-123")
            advanceUntilIdle()

            // After start + scheduler drain, the fan-in has re-reduced.
            assertEquals(SyncState.Idle, orchestrator.state.value)
        }

    // ── STOP fan-out (SCEN-STOP-1..4) ────────────────────────────────────

    @Test
    fun stop_callsStopOnAllLiveDomains() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            orchestrator.stop()

            coVerify(exactly = 1) { drive.stop() }
            coVerify(exactly = 1) { catalog.stop() }
            coVerify(exactly = 1) { progress.stop() }
        }

    @Test
    fun stop_partialFailure_closesRemainingChannels() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } throws RuntimeException("channel_busy")
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            orchestrator.stop() // must NOT throw

            coVerify(exactly = 1) { drive.stop() }
            coVerify(exactly = 1) { catalog.stop() }
            coVerify(exactly = 1) { progress.stop() }
            assertEquals(SyncState.Disabled, orchestrator.state.value)
        }

    @Test
    fun stop_idempotent_secondCall_doesNothing() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            orchestrator.stop()
            orchestrator.stop() // second call

            coVerify(exactly = 1) { drive.stop() }
            coVerify(exactly = 1) { catalog.stop() }
            coVerify(exactly = 1) { progress.stop() }
        }

    @Test
    fun stop_afterLogout_emitsDisabledEvenIfSessionGone() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            gateLive.value = false // simulate logout
            orchestrator.stop()

            coVerify(exactly = 1) { drive.stop() }
            coVerify(exactly = 1) { catalog.stop() }
            coVerify(exactly = 1) { progress.stop() }
            // Final state is Disabled — not Gated.
            assertEquals(SyncState.Disabled, orchestrator.state.value)
        }

    @Test
    fun stop_resetsPendingCountToZero() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs
            outboxPending.value = 7

            orchestrator.start("user-123")

            orchestrator.pendingCount.test {
                assertEquals(0, awaitItem())
                advanceUntilIdle()
                assertEquals(7, awaitItem())

                orchestrator.stop()
                advanceUntilIdle()
                assertEquals(0, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── PENDING aggregation (SCEN-PENDING-1..4) ──────────────────────────

    @Test
    fun pendingCount_emitsFromOutboxDao() =
        runTest {
            outboxPending.value = 0
            orchestrator.start("user-123")

            orchestrator.pendingCount.test {
                assertEquals(0, awaitItem())
                outboxPending.value = 5
                advanceUntilIdle()

                assertEquals(5, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun pendingCount_distinctUntilChanged_doesNotRepeat() =
        runTest {
            orchestrator.start("user-123")

            orchestrator.pendingCount.test {
                assertEquals(0, awaitItem())
                outboxPending.value = 3
                advanceUntilIdle()
                assertEquals(3, awaitItem())

                // Re-emitting the same value must not surface a duplicate.
                outboxPending.value = 3
                advanceUntilIdle()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── GATE → Gated state propagation ───────────────────────────────────

    @Test
    fun gateLost_emitsGatedSessionLost() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            advanceUntilIdle()

            orchestrator.state.test {
                assertEquals(SyncState.Idle, awaitItem())

                gateLive.value = false
                gateEvents.tryEmit(SessionEvent.Lost)
                advanceUntilIdle()

                assertEquals(SyncState.Gated("session_lost"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun gateExpired_emitsGatedWithReason() =
        runTest {
            coEvery { drive.stop() } just runs
            coEvery { catalog.stop() } just runs
            coEvery { progress.stop() } just runs

            orchestrator.start("user-123")
            advanceUntilIdle()

            orchestrator.state.test {
                assertEquals(SyncState.Idle, awaitItem())

                gateEvents.tryEmit(SessionEvent.Expired("refresh_token_revoked"))
                advanceUntilIdle()

                assertEquals(SyncState.Gated("refresh_token_revoked"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── DomainState mapping (table-driven sanity over the 5 DriveSyncState variants) ─

    @Test
    fun mapDrive_authorizationNeeded_becomesError() {
        assertEquals(
            DomainState.Error("Drive authorization needed"),
            mapDrive(DriveSyncState.AuthorizationNeeded),
        )
    }

    @Test
    fun mapDrive_idleRunningError_mapCorrectly() {
        assertEquals(DomainState.Idle, mapDrive(DriveSyncState.Idle))
        assertEquals(DomainState.Idle, mapDrive(DriveSyncState.Disabled))
        assertEquals(DomainState.Running, mapDrive(DriveSyncState.Running))
        assertEquals(DomainState.Error("boom"), mapDrive(DriveSyncState.Error("boom")))
    }

    // ── helpers ─────────────────────────────────────────────────────────
}

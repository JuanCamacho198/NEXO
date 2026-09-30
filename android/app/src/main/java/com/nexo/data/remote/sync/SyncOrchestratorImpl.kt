package com.nexo.data.remote.sync

import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.debug.DebugLog
import com.nexo.domain.sync.SessionEvent
import com.nexo.domain.sync.SessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * [SyncOrchestrator] implementation.
 *
 * Construction takes the three concrete syncer facades + a [SessionGate] +
 * the shared [SyncOutboxDao] + an external [CoroutineScope]. The orchestrator
 * owns a child scope built with [SupervisorJob] so a single failing per-domain
 * fan-out does not cancel siblings. [stop] finalization cancels the child scope
 * to detach the per-domain subscribers and pendingCount collector.
 *
 * Per-domain state fan-in: each facade's state is mapped to a common
 * [DomainState] and combined into [DomainStates]; the pure [reduce] function
 * then produces the aggregate [SyncState]. The same reducer is unit-tested in
 * isolation (table-driven).
 *
 * pendingCount: a single subscription on [SyncOutboxDao.observePendingCount].
 * The underlying table is shared across all three syncers, so summing per-
 * domain flows would double-count; we expose the table total directly with
 * distinctUntilChanged.
 */
class SyncOrchestratorImpl(
    private val drive: SyncService,
    private val catalog: SupabaseBookCatalogSync,
    private val progress: SupabaseProgressSync,
    private val gate: SessionGate,
    private val outboxDao: SyncOutboxDao,
    private val externalScope: CoroutineScope,
) : SyncOrchestrator {
    private val orchestratorScope: CoroutineScope =
        CoroutineScope(externalScope.coroutineContext + SupervisorJob())

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    override val pendingCount: Flow<Int> = _pendingCount.asStateFlow()

    private var started: Boolean = false
    private var stopped: Boolean = false
    private var pendingCountJob: Job? = null

    /** A5: last emitted outbox-depth bucket (emit only on bucket change). */
    @Volatile
    private var lastEmittedDepthBucket: Long = -1L

    /**
     * Subscribe to per-domain state flows + the gate. These subscriptions live
     * on the orchestrator-owned scope; cancelling that scope in [stop]
     * finalization detaches them.
     */
    init {
        combine(
            drive.syncState.map { mapDrive(it) },
            catalog.state.map { mapCatalog(it) },
            progress.state.map { mapProgress(it) },
        ) { d, c, p -> DomainStates(drive = d, catalog = c, progress = p) }
            .onEach { domains -> recompute(domains) }
            .launchIn(orchestratorScope)

        gate
            .sessionEvents()
            .onEach { event ->
                when (event) {
                    is SessionEvent.Lost -> _state.value = SyncState.Gated("session_lost")
                    is SessionEvent.Expired -> _state.value = SyncState.Gated(event.reason)
                    is SessionEvent.Live -> {
                        // Re-reduce so the aggregate picks the right variant.
                        recompute(snapshotDomains())
                    }
                }
            }.launchIn(orchestratorScope)
    }

    override suspend fun start(userId: String) {
        if (stopped) {
            DebugLog.warn(TAG, "start($userId) ignored — orchestrator has been stopped")
            return
        }
        if (started) {
            DebugLog.info(TAG, "start($userId) is a no-op — already started")
            return
        }
        started = true

        // Drive first (non-fatal: try/catch + WARN, but continue).
        try {
            val driveResult = drive.bootstrap(userId)
            if (driveResult.isFailure) {
                DebugLog.warn(TAG, "Drive bootstrap failed: ${driveResult.exceptionOrNull()?.message}")
            }
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Drive bootstrap threw: ${t.message}")
        }

        // Catalog second.
        try {
            catalog.bootstrap()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Catalog bootstrap threw: ${t.message}")
        }

        // Progress third (startProcessing + subscribeToRealtimeChanges).
        try {
            progress.startProcessing()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Progress startProcessing threw: ${t.message}")
        }
        try {
            progress.subscribeToRealtimeChanges()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Progress subscribeToRealtimeChanges threw: ${t.message}")
        }

        // Start the pendingCount collector (single shared DAO table).
        pendingCountJob?.cancel()
        pendingCountJob =
            outboxDao
                .observePendingCount()
                .distinctUntilChanged()
                .onEach { count ->
                    _pendingCount.value = count
                    // A5 - outbox depth gauge: bucketed, emitted only on bucket change.
                    val bucket =
                        com.nexo.debug.SentryMetrics
                            .bucketDepth(count)
                    if (bucket != lastEmittedDepthBucket) {
                        lastEmittedDepthBucket = bucket
                        com.nexo.debug.SentryMetrics
                            .notePendingCount(count)
                        com.nexo.debug.SentryMetrics.gauge(
                            "outbox_depth",
                            bucket,
                            mapOf(
                                "source" to "sync",
                                "platform" to "android",
                            ),
                        )
                    }
                }.launchIn(orchestratorScope)
    }

    override suspend fun stop() {
        if (stopped) return
        stopped = true
        started = false

        // Fan-out: each per-domain stop is isolated.
        try {
            drive.stop()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Drive stop threw: ${t.message}")
        }
        try {
            catalog.stop()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Catalog stop threw: ${t.message}")
        }
        try {
            progress.stop()
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "Progress stop threw: ${t.message}")
        }

        // Cancel the pendingCount collector and reset to 0.
        pendingCountJob?.cancel()
        pendingCountJob = null
        _pendingCount.value = 0

        // Emit Disabled (terminal until next start).
        _state.value = SyncState.Disabled

        // Finalize: cancel the orchestrator scope to detach per-domain subscribers.
        orchestratorScope.cancel()
    }

    override suspend fun schedulePush() {
        if (stopped) return
        drive.schedulePush()
        catalog.startProcessing()
        progress.startProcessing()
    }

    override suspend fun schedulePull() {
        if (stopped) return
        drive.schedulePull()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Snapshot of the current per-domain state by reading each facade's
     * StateFlow value. Used by the gate-driven re-reduce (no emission needed).
     */
    private fun snapshotDomains(): DomainStates =
        DomainStates(
            drive = mapDrive(drive.syncState.let { (it as? kotlinx.coroutines.flow.StateFlow<DriveSyncState>)?.value ?: DriveSyncState.Idle }),
            catalog = mapCatalog(catalog.state.value),
            progress = mapProgress(progress.state.value),
        )

    private fun recompute(domains: DomainStates) {
        _state.value =
            reduce(
                stopFlag = stopped,
                gateLive = gate.hasLiveSession(),
                domains = domains,
            )
    }

    companion object {
        private const val TAG = "SyncOrchestratorImpl"

        /**
         * Pure state reducer. Evaluated on every per-domain emission and on
         * every session-gate event. Returns the aggregate [SyncState].
         *
         * Priority (first match wins):
         * 1. [stopFlag] == true → [SyncState.Disabled].
         * 2. Any domain running → [SyncState.Active] (running beats error;
         *    an active domain means the user sees ongoing sync activity).
         * 3. Any domain error (and none running) → [SyncState.Error] with
         *    the first non-gated error message (drive → catalog → progress).
         * 4. Session gate closed → [SyncState.Gated] with the most recent
         *    per-domain gate-reason (or `"session_lost"` fallback).
         * 5. All idle + session live → [SyncState.Idle].
         */
        fun reduce(
            stopFlag: Boolean,
            gateLive: Boolean,
            domains: DomainStates,
        ): SyncState {
            if (stopFlag) return SyncState.Disabled

            if (domains.anyRunning()) return SyncState.Active

            val firstError = domains.firstNonGatedError()
            if (firstError != null) return SyncState.Error(firstError)

            val gateReason = domains.mostRecentGateReason()
            if (!gateLive || gateReason != null) {
                return SyncState.Gated(gateReason ?: "session_lost")
            }

            return SyncState.Idle
        }
    }
}

/**
 * Normalised per-domain state. The three facades (Drive, Catalog, Progress)
 * each map their concrete `State` sealed type into this common shape so the
 * orchestrator's [SyncOrchestratorImpl.reduce] stays uniform.
 */
sealed interface DomainState {
    data object Idle : DomainState

    data object Running : DomainState

    data class Gated(
        val reason: String,
    ) : DomainState

    data class Error(
        val message: String,
    ) : DomainState
}

data class DomainStates(
    val drive: DomainState,
    val catalog: DomainState,
    val progress: DomainState,
) {
    fun firstNonGatedError(): String? {
        listOf(drive, catalog, progress).forEach {
            if (it is DomainState.Error) return it.message
        }
        return null
    }

    fun anyRunning(): Boolean =
        drive is DomainState.Running ||
            catalog is DomainState.Running ||
            progress is DomainState.Running

    fun mostRecentGateReason(): String? =
        when (val p = progress) {
            is DomainState.Gated -> p.reason
            else -> null
        }
}

/**
 * Map the Drive-only [DriveSyncState] into the orchestrator's common
 * [DomainState]. AuthorizationNeeded is mapped to Error so it surfaces in the
 * union (DebugViewModel can still distinguish by message text).
 */
fun mapDrive(state: DriveSyncState): DomainState =
    when (state) {
        is DriveSyncState.Idle -> DomainState.Idle
        is DriveSyncState.Disabled -> DomainState.Idle
        is DriveSyncState.Running -> DomainState.Running
        is DriveSyncState.Error -> DomainState.Error(state.message)
        is DriveSyncState.AuthorizationNeeded -> DomainState.Error("Drive authorization needed")
    }

/**
 * Map SupabaseProgressSync.State into the orchestrator's common
 * [DomainState]. Preserves the Gated reason verbatim (DebugViewModel contract).
 */
fun mapProgress(state: SupabaseProgressSync.State): DomainState =
    when (state) {
        is SupabaseProgressSync.State.Idle -> DomainState.Idle
        is SupabaseProgressSync.State.Running -> DomainState.Running
        is SupabaseProgressSync.State.Gated -> DomainState.Gated(state.reason)
        is SupabaseProgressSync.State.Error -> DomainState.Error(state.message)
    }

/**
 * Map SupabaseBookCatalogSync.State into the orchestrator's common
 * [DomainState].
 */
fun mapCatalog(state: SupabaseBookCatalogSync.State): DomainState =
    when (state) {
        is SupabaseBookCatalogSync.State.Idle -> DomainState.Idle
        is SupabaseBookCatalogSync.State.Running -> DomainState.Running
        is SupabaseBookCatalogSync.State.Error -> DomainState.Error(state.message)
    }

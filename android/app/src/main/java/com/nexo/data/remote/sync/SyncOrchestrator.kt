package com.nexo.data.remote.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level sync lifecycle handle for the three per-domain syncers
 * (Drive cold-backup, Supabase catalog, Supabase progress).
 *
 * PR-2 of sync-layer-split adds this interface; consumers are NOT yet wired in
 * this PR — `AuthViewModel.triggerSyncForSession` / `onLogout` migrate in PR-3.
 * Existing per-domain handles (`syncService`, `supabaseProgressSync`,
 * `supabaseBookCatalogSync`) stay reachable from `AppContainer` so domain-shaped
 * entry points (`pullAllHighlights`, `resumeForBook`, `getDownloadableBooks`,
 * `downloadRemoteBook`) keep working unchanged.
 *
 * Contract:
 * - [start] runs Drive first (non-fatal), then Catalog, then Progress — each
 *   in its own try/catch. Partial failure does NOT abort the rest.
 * - [stop] fans out to every per-domain `stop()` (added to Drive in PR-2).
 *   Idempotent; the second call is a no-op.
 * - [state] emits the aggregate [SyncState] union; transitions are reduced
 *   from per-domain + session-gate signals via a pure function
 *   ([SyncOrchestratorImpl.reduce]) so the rules are table-testable.
 * - [pendingCount] sums the per-domain pending counts (single shared outbox
 *   DAO table; sum collapses to the table total).
 */
interface SyncOrchestrator {
    /**
     * Cold-start sync for a freshly-authenticated user.
     *
     * Order is fixed: Drive bootstrap (try/catch, non-fatal) → Catalog bootstrap
     * (try/catch) → Progress startProcessing + subscribeToRealtimeChanges
     * (each in try/catch). Catalog and Progress only run after Drive bootstrap
     * returns (or throws). Idempotent: a second call while a previous one is
     * active does NOT reissue per-domain bootstraps.
     */
    suspend fun start(userId: String)

    /**
     * Idempotent stop. Calls `stop()` on every per-domain syncer in its own
     * try/catch — one failing does not block the others. Emits
     * [SyncState.Disabled] at the end. The second call short-circuits.
     */
    suspend fun stop()

    /**
     * Schedule a push pass across all live domains (delegates to existing
     * `schedulePush` / `processOutbox` paths).
     */
    suspend fun schedulePush()

    /**
     * Schedule a pull pass across all live domains.
     */
    suspend fun schedulePull()

    /**
     * Aggregate state across Drive + Catalog + Progress + SessionGate.
     *
     * Aggregation rules are defined in [SyncState] KDoc and implemented by
     * [SyncOrchestratorImpl.reduce] (a pure top-level function).
     */
    val state: StateFlow<SyncState>

    /**
     * Combined pending count (badge consumers). Distinct-until-changed; the
     * underlying table is shared so the per-domain sum collapses to the
     * global `outboxDao.observePendingCount()` emission.
     */
    val pendingCount: Flow<Int>
}

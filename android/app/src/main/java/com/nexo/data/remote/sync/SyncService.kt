package com.nexo.data.remote.sync

import kotlinx.coroutines.flow.Flow

/**
 * Drive-only sync state.
 *
 * Renamed from `SyncState` (PR-2 of sync-layer-split) to make room for the
 * aggregate [SyncState] union produced by [SyncOrchestrator]. This type stays
 * confined to the Drive cold-backup facade (`GoogleDriveSyncService`,
 * `DriveColdBackupService`); the orchestrator's three-domain reducer maps it
 * into the union via `SyncOrchestratorImpl.reduce`.
 *
 * The variants are unchanged from the pre-rename type:
 * - [Idle]        — Drive syncer ready, no activity.
 * - [Disabled]    — Drive is not configured / disabled by user / shutdown.
 * - [Running]     — Drive push/pull in flight.
 * - [Error]       — Drive returned a typed error.
 * - [AuthorizationNeeded] — Drive returned 401/403 and refresh token cannot
 *                          re-issue access; UI prompts re-authorization.
 */
sealed class DriveSyncState {
    data object Idle : DriveSyncState()

    data object Disabled : DriveSyncState()

    data object Running : DriveSyncState()

    data class Error(
        val message: String,
    ) : DriveSyncState()

    /**
     * Drive returned 401/403 and the refresh token could not re-issue access.
     * The UI should prompt the user to re-authorize Drive.
     */
    data object AuthorizationNeeded : DriveSyncState()
}

/**
 * SyncService — Drive cold backup vs Supabase hot SoT (PR2).
 * Hot sync for reading_progress/highlights/bookmarks/sessions is Supabase only
 * via PostgREST onConflict gated by hasLiveSession + single Realtime supervisor
 * (progress:uid/highlights:uid/bookmarks:uid/sessions:uid), LWW version+1.
 * Drive is cold Export/Import only — no hot push/pull for state on save/open.
 *
 * Post PR-2 of sync-layer-split, `syncState` is typed [DriveSyncState] (the
 * Drive-only union). The aggregate union over Drive + Catalog + Progress lives
 * in [SyncState] and is exposed via [SyncOrchestrator.state].
 */
interface SyncService {
    val syncState: Flow<DriveSyncState>
    val pendingCount: Flow<Int>

    suspend fun bootstrap(userId: String): Result<Unit>

    suspend fun schedulePush(): Result<Unit>

    suspend fun schedulePull(): Result<Unit>

    /**
     * Idempotent stop. PR-2 of sync-layer-split: added so the orchestrator's
     * fan-out `stop()` can call a uniform per-domain `stop()` without
     * branching on facade shape. Cancels any in-flight push/pull and flips
     * the Drive state to [DriveSyncState.Disabled]. The cold-backup service
     * (Settings-only Export/Import) is unaffected — this only stops the
     * hot Drive sync facade.
     */
    suspend fun stop()
}

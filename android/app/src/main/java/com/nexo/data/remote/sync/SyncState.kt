package com.nexo.data.remote.sync

/**
 * Aggregate sync state for [SyncOrchestrator].
 *
 * Combines the per-domain [com.nexo.data.remote.supabase.SupabaseProgressSync.State],
 * [com.nexo.data.remote.supabase.SupabaseBookCatalogSync.State], and
 * [DriveSyncState] (the renamed Drive-only type previously known as `SyncState`)
 * into a single union that consumers — SyncStatusIndicator, DebugViewModel,
 * and (in PR-3) AuthViewModel — read once instead of per-domain.
 *
 * Aggregation rules are encoded by [SyncOrchestratorImpl.reduce]:
 * - `stop()` has run → [Disabled] (terminal until next `start()`)
 * - Session lost / gate closed (no domain error) → [Gated] with the most
 *   recent per-domain gate-reason string (or `"session_lost"` if none).
 * - Any domain is `Error` (and none is `Running`) → [Error] (first non-gated
 *   domain error wins).
 * - Any domain is `Running` → [Active].
 * - All domains are `Idle` and session is live → [Idle].
 *
 * **DebugViewModel compatibility**: [Gated.reason] preserves the verbatim
 * per-domain gate-reason string so the debug screen's existing label mapping
 * (`"session_expired"`, `"refresh_token_revoked"`, …) keeps working unchanged.
 */
sealed interface SyncState {
    /** All domains idle; session live. */
    data object Idle : SyncState

    /** At least one domain is currently Running. */
    data object Active : SyncState

    /**
     * Session lost / refresh failed. [reason] carries the most recent
     * per-domain gate-reason string verbatim (DebugViewModel contract).
     */
    data class Gated(
        val reason: String,
    ) : SyncState

    /**
     * At least one domain errored and no domain is currently Running.
     * [message] is the first non-gated domain error message.
     */
    data class Error(
        val message: String,
    ) : SyncState

    /** Terminal after [SyncOrchestrator.stop]; lifts on the next `start()`. */
    data object Disabled : SyncState
}

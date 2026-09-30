package com.nexo.domain.sync

import com.nexo.domain.model.AuthSession
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for "should sync run right now?".
 *
 * Wraps [com.nexo.data.session.SessionManager] so that callers
 * (the future `SyncOrchestrator` and each per-domain syncer's gated backoff loop)
 * don't talk to the underlying session layer directly. The gate is pure domain
 * code; the Android-aware implementation lives in `data/sync/`.
 *
 * Contract:
 * - [hasLiveSession] is cheap, safe to call from any dispatcher, and never
 *   performs network I/O.
 * - [ensureFreshSession] returns [Result.success] on a freshly-refreshed session
 *   or [Result.failure] wrapping the underlying [Throwable]. It never throws.
 * - [sessionEvents] emits at every fresh-session boundary. [SessionEvent.Live]
 *   when a fresh session is established, [SessionEvent.Lost] when invalidated
 *   (logout/revocation), and [SessionEvent.Expired] when a refresh attempt fails
 *   with the typed reason string.
 */
interface SessionGate {
    /** True iff a cached, valid session exists. Cheap; no network I/O. */
    fun hasLiveSession(): Boolean

    /** Force a token refresh; wrap any thrown exception in [Result.failure]. */
    suspend fun ensureFreshSession(): Result<AuthSession>

    /** Stream of session-lifecycle events for orchestrator/syncer consumers. */
    fun sessionEvents(): Flow<SessionEvent>
}

sealed interface SessionEvent {
    /** A fresh, valid session has been established. */
    data object Live : SessionEvent

    /** The session was invalidated (logout, token revocation). */
    data object Lost : SessionEvent

    /** A refresh attempt failed with the given reason string. */
    data class Expired(
        val reason: String,
    ) : SessionEvent
}

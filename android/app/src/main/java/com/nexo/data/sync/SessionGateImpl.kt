package com.nexo.data.sync

import com.nexo.data.session.SessionManager
import com.nexo.debug.DebugLog
import com.nexo.domain.model.AuthSession
import com.nexo.domain.sync.SessionEvent
import com.nexo.domain.sync.SessionGate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [SessionGate] impl backed by the existing [SessionManager].
 *
 * - [hasLiveSession] reads a cached boolean maintained by [ensureFreshSession],
 *   [onSessionLost], and [onSessionRestored]. It is a local read — no suspend,
 *   no network I/O, never throws. The cache starts `false` until the first
 *   [ensureFreshSession] (or [onSessionRestored]) call establishes it.
 * - [ensureFreshSession] delegates to [SessionManager.ensureFreshSession],
 *   converts any thrown exception into [Result.failure], and emits
 *   [SessionEvent.Live] on success or [SessionEvent.Expired] on failure.
 *
 * Lost/Live boundary events are pushed via [MutableSharedFlow] (`replay = 0`,
 * `extraBufferCapacity = 8`, `DROP_OLDEST`). Consumers MUST be subscribed
 * before the emission arrives — the orchestrator in PR-2 owns the lifecycle
 * subscription.
 */
class SessionGateImpl(
    private val sessionManager: SessionManager,
) : SessionGate {
    @Volatile
    private var live: Boolean = false

    private val _sessionEvents =
        MutableSharedFlow<SessionEvent>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun hasLiveSession(): Boolean = live

    override suspend fun ensureFreshSession(): Result<AuthSession> =
        try {
            val result = sessionManager.ensureFreshSession()
            if (result.isSuccess) {
                live = true
                _sessionEvents.tryEmit(SessionEvent.Live)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "ensureFreshSession failed"
                DebugLog.warn(TAG, "ensureFreshSession failed: $reason")
                _sessionEvents.tryEmit(SessionEvent.Expired(reason))
            }
            result
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "ensureFreshSession threw: ${t.message}")
            _sessionEvents.tryEmit(SessionEvent.Expired(t.message ?: t::class.simpleName.orEmpty()))
            Result.failure(t)
        }

    override fun sessionEvents(): Flow<SessionEvent> = _sessionEvents.asSharedFlow()

    /**
     * Signal that the session has been invalidated (logout / token revocation).
     * The orchestrator (PR-2) owns calling this on `onLogout`.
     */
    fun onSessionLost() {
        live = false
        _sessionEvents.tryEmit(SessionEvent.Lost)
    }

    /**
     * Signal that a session has been restored after being lost. The orchestrator
     * (PR-2) owns calling this on successful `start()`.
     */
    fun onSessionRestored() {
        live = true
        _sessionEvents.tryEmit(SessionEvent.Live)
    }

    companion object {
        private const val TAG = "SessionGateImpl"
    }
}

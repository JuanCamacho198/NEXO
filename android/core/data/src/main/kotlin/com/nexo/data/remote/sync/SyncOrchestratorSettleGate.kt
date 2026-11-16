package com.nexo.data.remote.sync

import com.nexo.domain.sync.SyncSettleGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SyncSettleGate] over the existing [SyncOrchestrator] aggregate state.
 *
 * Settled means the aggregate is anything other than [SyncState.Active]
 * (`Idle`, `Gated`, `Error`, `Disabled`). The wait is bounded so a permanently
 * active sync never blocks a delete forever; on timeout the caller treats sync
 * as unsettled and leaves cleanup to the orphan sweep.
 */
class SyncOrchestratorSettleGate(
    private val orchestrator: SyncOrchestrator,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SyncSettleGate {
    override suspend fun awaitSettled(): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            orchestrator.state.first { it !is SyncState.Active }
        } != null

    companion object {
        /** Upper bound on how long a delete waits for sync to settle. */
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}

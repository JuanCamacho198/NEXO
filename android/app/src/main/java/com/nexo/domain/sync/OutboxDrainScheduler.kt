package com.nexo.domain.sync

/**
 * Domain seam for scheduling an outbox drain.
 *
 * This is a SCHEDULER-ONLY contract: implementations decide *when* the existing
 * sync entry points run again (after an outbox write or at app start) and MUST
 * NOT reimplement the drain. All sync semantics — LWW version+1 with the
 * recordId tie-break, the tombstone resurrection guard, FK guards, coalescing by
 * (type, bookId), the poison threshold, the gated session-backoff counter, and
 * the settle-gate/orphan-sweep ordering — stay inside the existing sync
 * services. Realtime stays in-process and is never moved into a worker.
 *
 * Kept in `domain` with no Android dependency so writer repositories can depend
 * on it without importing WorkManager (the implementation lives in the data
 * layer).
 */
interface OutboxDrainScheduler {
    /**
     * Requests one drain. Implementations resolve the current user internally
     * and no-op when there is no authenticated session.
     */
    suspend fun scheduleDrain()

    companion object {
        /**
         * No-op scheduler for non-wired callers and tests (keeps direct
         * construction sites — e.g. ViewModel tests — free of a real
         * WorkManager dependency).
         */
        val NoOp: OutboxDrainScheduler =
            object : OutboxDrainScheduler {
                override suspend fun scheduleDrain() = Unit
            }
    }
}

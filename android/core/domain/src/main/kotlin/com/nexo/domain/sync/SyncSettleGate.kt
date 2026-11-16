package com.nexo.domain.sync

/**
 * Reports whether the sync layer has settled (no domain is actively syncing).
 *
 * Local-only file cleanup runs only once this returns `true`, so it never
 * races an in-flight Drive/catalog/progress sync. A `false` result means sync
 * did not settle in the allotted time: the caller must leave the work to the
 * orphan sweep rather than block on it.
 */
fun interface SyncSettleGate {
    /**
     * Suspends until sync settles, or a bounded timeout elapses.
     *
     * @return `true` when sync has settled, `false` when it did not settle in time.
     */
    suspend fun awaitSettled(): Boolean
}

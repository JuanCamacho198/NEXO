package com.nexo.debug

/** Session-scoped snapshot of the last sync outcome observed by the toolkit. */
data class DebugSyncStatus(
    val lastSyncAtMs: Long? = null,
    val lastFailure: String? = null,
)

/**
 * In-memory record of the last debug-toolkit-triggered sync outcome (FR-AD2
 * sync status). The panel's force-sync actions write here and the readout
 * reads it, so "last sync time + last failure" has a real local source when
 * the Drive state alone cannot provide a timestamp.
 *
 * Deliberately ephemeral: no persistence, no network, no upload. This is not a
 * telemetry sink. Reset in tests via [reset].
 */
object DebugSyncStatusStore {
    private var lastSyncAtMs: Long? = null
    private var lastFailure: String? = null

    @Synchronized
    fun recordSuccess(nowMs: Long = System.currentTimeMillis()) {
        lastSyncAtMs = nowMs
        lastFailure = null
    }

    @Synchronized
    fun recordFailure(
        message: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        lastSyncAtMs = nowMs
        lastFailure = message
    }

    @Synchronized
    fun snapshot(): DebugSyncStatus = DebugSyncStatus(lastSyncAtMs = lastSyncAtMs, lastFailure = lastFailure)

    @Synchronized
    fun reset() {
        lastSyncAtMs = null
        lastFailure = null
    }
}

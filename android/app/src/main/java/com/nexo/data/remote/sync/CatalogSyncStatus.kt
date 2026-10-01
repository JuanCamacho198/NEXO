package com.nexo.data.remote.sync

import com.nexo.data.remote.drive.SyncErrorCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aggregate of book-catalog upsert failures observed by the BACKGROUND catalog
 * sync (outbox drain + reconciliation).
 *
 * The catalog sync runs on a timer/outbox — a toast per cycle would spam the
 * user. Instead each failing book is recorded here with its stable error code
 * (see [SyncErrorCodes]) and removed again when that same book later reaches
 * the catalog. An empty aggregate renders nothing; the Data & Storage settings
 * panel shows a single status line otherwise.
 *
 * Only the typed code is retained — never the raw message — so nothing that
 * could carry a token, JWT, or credential reaches the UI.
 */
data class CatalogSyncFailureReport(
    val failedCount: Int,
    val codes: List<String>,
)

object CatalogSyncStatus {
    private val lock = Any()
    private val failures = linkedMapOf<String, String>()
    private val _report = MutableStateFlow<CatalogSyncFailureReport?>(null)
    val report: StateFlow<CatalogSyncFailureReport?> = _report.asStateFlow()

    /** Record a per-book catalog failure. A repeat overwrites the code, never inflates the count. */
    fun recordFailure(
        bookId: String,
        code: String,
    ) {
        synchronized(lock) {
            failures[bookId] = code.ifBlank { SyncErrorCodes.UNAVAILABLE }
            _report.value = snapshot()
        }
    }

    /** Remove a book once its catalog row later succeeds. */
    fun recordSuccess(bookId: String) {
        synchronized(lock) {
            if (failures.remove(bookId) != null) {
                _report.value = snapshot()
            }
        }
    }

    /** Reset the aggregate (sign-out, tests). */
    fun clear() {
        synchronized(lock) {
            failures.clear()
            _report.value = null
        }
    }

    private fun snapshot(): CatalogSyncFailureReport? {
        if (failures.isEmpty()) return null
        return CatalogSyncFailureReport(
            failedCount = failures.size,
            codes = failures.values.distinct(),
        )
    }
}

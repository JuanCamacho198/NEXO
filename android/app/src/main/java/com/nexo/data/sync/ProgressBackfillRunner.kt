package com.nexo.data.sync

import com.nexo.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Auth-independent entry point for the FR-10 cache -> canonical backfill.
 *
 * [ProgressReconciler.reconcileAll] used to be reachable only through
 * `GoogleDriveSyncService.bootstrap`, which is session-gated. A legacy device
 * that never signs in would therefore never backfill, and a WU2b column drop
 * could lose a reading position that lived only in the retired cache column.
 *
 * This runner exposes the same idempotent reconcile on a purely local path that
 * is invoked at app start, with no Drive login or session anywhere in the call
 * chain. It is a bounded local Room read, so it runs asynchronously on an IO
 * scope rather than through WorkManager: startup is never blocked, and the
 * backfill does not depend on the background scheduler eventually running it.
 */
class ProgressBackfillRunner(
    private val reconciler: ProgressReconciler,
    private val scope: CoroutineScope,
) {
    fun schedule(): Job =
        scope.launch {
            runCatching { reconciler.reconcileAll() }
                .onFailure { DebugLog.warn(COMPONENT, "auth-independent progress backfill failed: ${it.message}") }
        }

    private companion object {
        const val COMPONENT = "ProgressBackfillRunner"
    }
}

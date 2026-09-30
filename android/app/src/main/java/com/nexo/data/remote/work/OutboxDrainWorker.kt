package com.nexo.data.remote.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot drain worker (S6).
 *
 * This worker is a SCHEDULER, not a drain implementation: it only re-invokes the
 * existing sync entry points and returns immediately. `startProcessing()` is
 * non-suspending and already launches the in-process, session-gated drain loop,
 * so all drain semantics stay untouched here (LWW/tie-break, tombstone guard,
 * FK guards, coalescing, poison-3, gated backoff, settle gate). The worker
 * deliberately never calls `Result.retry()`: WorkManager backoff would duplicate
 * the in-process gated backoff and could double-drain.
 */
@HiltWorker
class OutboxDrainWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val progressSync: SupabaseProgressSync,
        private val catalogSync: SupabaseBookCatalogSync,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            progressSync.startProcessing()
            catalogSync.startProcessing()
            return Result.success()
        }
    }

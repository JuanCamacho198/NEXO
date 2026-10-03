package com.nexo.data.remote.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.data.local.RetentionPruner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * FR-14: periodic retention worker. Prunes soft-deleted rows older than the
 * 30-day threshold and vacuums Room, then exits. Scheduler-only (`see
 * [WorkManagerRetentionPruneScheduler]`); the prune implementation lives in
 * [RetentionPruner].
 */
@HiltWorker
class RetentionPruneWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val pruner: RetentionPruner,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            runCatching { pruner.pruneAndVacuum() }
                .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

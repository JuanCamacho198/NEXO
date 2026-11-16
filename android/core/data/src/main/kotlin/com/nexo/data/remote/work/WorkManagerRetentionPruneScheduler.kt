package com.nexo.data.remote.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * FR-14 WorkManager-backed retention scheduler (decision 0.2). Enqueues ONE
 * unique periodic [RetentionPruneWorker] with [ExistingPeriodicWorkPolicy.KEEP],
 * so repeated app starts never re-schedule or duplicate the prune. WorkManager
 * is resolved lazily on first [schedule] so container construction stays
 * side-effect free.
 */
class WorkManagerRetentionPruneScheduler(
    private val context: Context,
    private val workManager: WorkManager? = null,
) {
    fun schedule() {
        workManager().enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(),
        )
    }

    internal fun buildRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<RetentionPruneWorker>(INTERVAL_DAYS, TimeUnit.DAYS).build()

    private fun workManager(): WorkManager = workManager ?: WorkManager.getInstance(context.applicationContext)

    companion object {
        const val WORK_NAME = "retention-prune"

        /** Weekly prune (FR-14). */
        const val INTERVAL_DAYS = 7L
    }
}

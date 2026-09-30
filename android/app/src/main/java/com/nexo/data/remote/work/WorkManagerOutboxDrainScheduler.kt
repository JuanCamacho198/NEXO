package com.nexo.data.remote.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nexo.data.session.SessionManager
import com.nexo.domain.sync.OutboxDrainScheduler
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed [OutboxDrainScheduler] (S6).
 *
 * Enqueues **unique one-shot** work named `outbox-drain-<userId>` with
 * [ExistingWorkPolicy.KEEP], so concurrent drain requests de-dup instead of
 * double-running, and work is retried by WorkManager with a
 * [NetworkType.CONNECTED] constraint and exponential backoff. No periodic worker
 * is ever scheduled (spec: one-shot only).
 *
 * Note on the WorkManager resolution: the design pinned the constructor default
 * `workManager: WorkManager = WorkManager.getInstance(context)`, which would
 * initialize WorkManager eagerly while [com.nexo.di.AppContainer] is being
 * built (before its `Configuration.Provider` is guaranteed ready). The
 * implementation instead resolves the instance lazily on the first enqueue and
 * accepts an injected instance for tests. Construction is therefore side-effect
 * free and the cold-start partition is preserved.
 */
class WorkManagerOutboxDrainScheduler(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val workManager: WorkManager? = null,
) : OutboxDrainScheduler {
    override suspend fun scheduleDrain() {
        val userId = sessionManager.getCurrentSession().getOrNull()?.userId ?: return
        workManager().enqueueUniqueWork(
            drainWorkName(userId),
            ExistingWorkPolicy.KEEP,
            buildDrainRequest(),
        )
    }

    internal fun buildDrainRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<OutboxDrainWorker>()
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

    internal fun drainWorkName(userId: String): String = "$DRAIN_WORK_PREFIX$userId"

    private fun workManager(): WorkManager = workManager ?: WorkManager.getInstance(context.applicationContext)

    companion object {
        internal const val DRAIN_WORK_PREFIX = "outbox-drain-"

        /** Exponential backoff initial delay; WorkManager doubles it on retry. */
        internal const val BACKOFF_DELAY_SECONDS = 30L
    }
}

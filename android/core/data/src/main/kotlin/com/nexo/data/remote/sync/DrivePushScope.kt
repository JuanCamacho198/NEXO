package com.nexo.data.remote.sync

import com.nexo.debug.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-lifetime scope a Drive push runs on (NEXO-ANDROID-R).
 *
 * Extracted from [GoogleDriveSyncService] so the "which scope does the upload
 * belong to" decision lives in exactly one place, and so the service itself does
 * not grow a second concurrency concern alongside its push/pull orchestration.
 *
 * Why this exists at all: the push used to be launched from the UI
 * (`rememberCoroutineScope()` in `DrivePromptHost`). Once Drive was genuinely
 * authorized the push started doing seconds of real upload work, so leaving the
 * screen — or an Activity recreation on a config change, which `MainActivity`
 * allows because it declares no `android:configChanges` — cancelled the upload
 * mid-flight and surfaced
 * `SYNC_BOOK_PUSH_FAILED — rememberCoroutineScope left the composition`.
 *
 * The scope here is deliberately NOT a Compose scope, not a ViewModel scope, and
 * not the caller's scope. [SupervisorJob] so one failed push cannot cancel the
 * scope or its siblings; modelled on the sibling syncer `SupabaseProgressSync`,
 * which owns an identical `CoroutineScope(SupervisorJob() + ioDispatcher)`.
 */
class DrivePushScope(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** In-flight push, so concurrent triggers de-dup instead of stacking. */
    private var inFlight: Job? = null

    /**
     * Runs [work] on the process-lifetime scope and returns its [Job].
     *
     * The returned job is exposed so callers and tests can observe completion;
     * production UI ignores it. Cancelling it does NOT represent leaving a
     * screen — there is nothing to leave, because this scope is not bound to any
     * screen or ViewModel.
     */
    fun launch(work: suspend () -> Unit): Job {
        val running = inFlight
        if (running?.isActive == true) {
            DebugLog.info(TAG, "background push already in flight, reusing it")
            return running
        }
        val job = scope.launch { work() }
        inFlight = job
        return job
    }

    private companion object {
        const val TAG = "DrivePushScope"
    }
}

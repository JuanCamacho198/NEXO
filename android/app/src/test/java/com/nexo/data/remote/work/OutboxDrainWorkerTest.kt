package com.nexo.data.remote.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 — drain invariants at the worker boundary (T7.2).
 *
 * The worker is a SCHEDULER, not a drain: it must delegate to the existing
 * `startProcessing()` entry points, return success, and never reimplement or
 * bypass the frozen semantics. These tests pin the exact boundary — the only two
 * collaborators and the only call the worker may make on them — so a future edit
 * cannot quietly turn the worker into a second drain path (which would duplicate
 * the in-process gated counter/`retry_count` handling and the coalescing logic).
 *
 * `SupabaseProgressSync`/`SupabaseBookCatalogSync` are mocked at that boundary:
 * the LWW/tie-break, tombstone guard, FK guards, coalescing, poison-3 and the
 * gated counter never run here — they stay inside the mocked entry point, and
 * the full sync suite (unchanged) owns their behavior.
 */
class OutboxDrainWorkerTest {
    @Test
    fun doWork_delegatesToExistingSyncEntryPoints_andReturnsSuccess() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val worker = outboxDrainWorker(progressSync, catalogSync)

            val result = worker.doWork()

            verify(exactly = 1) { progressSync.startProcessing() }
            verify(exactly = 1) { catalogSync.startProcessing() }
            assertEquals(ListenableWorker.Result.success(), result)
        }

    /**
     * The worker must report `Success` and never `Retry`: a WorkManager retry would
     * layer a second backoff on top of the in-process gated backoff and could
     * double-drain. `confirmVerified` additionally proves the worker issued no
     * other call on its collaborators — it holds no outbox/commit handle, so it
     * cannot touch the gated counter or `retry_count` itself.
     */
    @Test
    fun doWork_returnsSuccessNotRetry_andIssuesNoOtherInteraction() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val worker = outboxDrainWorker(progressSync, catalogSync)

            val result = worker.doWork()

            verify(exactly = 1) { progressSync.startProcessing() }
            verify(exactly = 1) { catalogSync.startProcessing() }
            confirmVerified(progressSync, catalogSync)

            assertTrue("worker must report Success, got $result", result is ListenableWorker.Result.Success)
            assertFalse(
                "worker must not request a WorkManager retry (duplicates the gated backoff)",
                result is ListenableWorker.Result.Retry,
            )
        }

    // ── helpers ─────────────────────────────────────────────────────

    private fun outboxDrainWorker(
        progressSync: SupabaseProgressSync,
        catalogSync: SupabaseBookCatalogSync,
    ) = OutboxDrainWorker(
        appContext = mockk<Context>(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true),
        progressSync = progressSync,
        catalogSync = catalogSync,
    )

    private fun stubProgressSync(): SupabaseProgressSync = mockk<SupabaseProgressSync>().also { every { it.startProcessing() } just Runs }

    private fun stubCatalogSync(): SupabaseBookCatalogSync = mockk<SupabaseBookCatalogSync>().also { every { it.startProcessing() } just Runs }
}

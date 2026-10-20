package com.nexo.data.remote.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.SyncService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
 * entry points, return success, and never reimplement or bypass the frozen
 * semantics. These tests pin the exact boundary — the collaborators and the
 * calls the worker may make on them — so a future edit cannot quietly turn the
 * worker into a second drain path (which would duplicate the in-process gated
 * counter/`retry_count` handling and the coalescing logic).
 *
 * The Drive push ([SyncService.schedulePush]) runs FIRST and must be present:
 * it is the only consumer that sets `remotePath`, which the catalog drain
 * defers on. Without it a BOOK outbox row stays parked forever.
 *
 * `SupabaseProgressSync`/`SupabaseBookCatalogSync`/`SyncService` are mocked at
 * that boundary: the LWW/tie-break, tombstone guard, FK guards, coalescing,
 * poison-3 and the gated counter never run here — they stay inside the mocked
 * entry point, and the full sync suite (unchanged) owns their behavior.
 */
class OutboxDrainWorkerTest {
    @Test
    fun doWork_delegatesToExistingSyncEntryPoints_andReturnsSuccess() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val driveSync = stubDriveSync()
            val worker = outboxDrainWorker(progressSync, catalogSync, driveSync)

            val result = worker.doWork()

            verify(exactly = 1) { progressSync.startProcessing() }
            verify(exactly = 1) { catalogSync.startProcessing() }
            coVerify(exactly = 1) { driveSync.schedulePush() }
            assertEquals(ListenableWorker.Result.success(), result)
        }

    /**
     * Ordering invariant: the Drive push must run before the catalog drain. The
     * catalog defers any book whose `remotePath` is still blank, so a catalog
     * pass that runs first would park the row; running the Drive push first lets
     * it set `remotePath` in the same drain.
     */
    @Test
    fun doWork_runsDrivePushBeforeCatalog_soRemotePathIsSetFirst() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val driveSync = stubDriveSync()
            val worker = outboxDrainWorker(progressSync, catalogSync, driveSync)

            worker.doWork()

            coVerifyOrder {
                driveSync.schedulePush()
                catalogSync.startProcessing()
            }
        }

    /**
     * A throwing Drive push (disabled, unauthenticated, network) must not stop
     * the catalog/progress drains: the push is best-effort at this boundary.
     */
    @Test
    fun doWork_drivePushThrows_stillDrainsCatalogAndProgress() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val driveSync = mockk<SyncService>(relaxed = true).also {
                coEvery { it.schedulePush() } throws IllegalStateException("drive disabled")
            }
            val worker = outboxDrainWorker(progressSync, catalogSync, driveSync)

            val result = worker.doWork()

            verify(exactly = 1) { progressSync.startProcessing() }
            verify(exactly = 1) { catalogSync.startProcessing() }
            assertTrue("worker must report Success, got $result", result is ListenableWorker.Result.Success)
        }

    /**
     * The worker must report `Success` and never `Retry`: a WorkManager retry would
     * layer a second backoff on top of the in-process gated backoff and could
     * double-drain.
     */
    @Test
    fun doWork_returnsSuccessNotRetry() =
        runTest {
            val progressSync = stubProgressSync()
            val catalogSync = stubCatalogSync()
            val driveSync = stubDriveSync()
            val worker = outboxDrainWorker(progressSync, catalogSync, driveSync)

            val result = worker.doWork()

            coVerify(exactly = 1) { driveSync.schedulePush() }
            verify(exactly = 1) { progressSync.startProcessing() }
            verify(exactly = 1) { catalogSync.startProcessing() }
            confirmVerified(progressSync, catalogSync, driveSync)

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
        driveSync: SyncService,
    ) = OutboxDrainWorker(
        appContext = mockk<Context>(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true),
        progressSync = progressSync,
        catalogSync = catalogSync,
        driveSync = driveSync,
    )

    private fun stubProgressSync(): SupabaseProgressSync = mockk<SupabaseProgressSync>().also { every { it.startProcessing() } just Runs }

    private fun stubCatalogSync(): SupabaseBookCatalogSync = mockk<SupabaseBookCatalogSync>().also { every { it.startProcessing() } just Runs }

    private fun stubDriveSync(): SyncService =
        mockk<SyncService>(relaxed = true).also { coEvery { it.schedulePush() } returns Result.success(Unit) }
}

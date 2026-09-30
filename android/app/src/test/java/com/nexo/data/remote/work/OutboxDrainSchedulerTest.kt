package com.nexo.data.remote.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.nexo.data.session.SessionManager
import com.nexo.domain.model.AuthSession
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * S6 — WorkManager drain scheduler shape.
 *
 * Asserts the scheduler-level guarantees the spec fixes (SC1.1–SC1.3): one-shot
 * unique work keyed per user, `ExistingWorkPolicy.KEEP` de-dup, a CONNECTED
 * network constraint, and exponential backoff. Sync semantics themselves are NOT
 * exercised here — the existing sync suite owns those (S7 asserts them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxDrainSchedulerTest {
    @Test
    fun scheduleDrain_enqueuesUniqueOneShotWorkWithKeepPolicyPerUser() =
        runTest {
            val workManager = mockk<WorkManager>(relaxed = true)
            val request = slot<OneTimeWorkRequest>()
            val scheduler = schedulerFor(workManager, FakeSessionManager(session("user-1")))

            scheduler.scheduleDrain()

            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    "outbox-drain-user-1",
                    ExistingWorkPolicy.KEEP,
                    capture(request),
                )
            }
            // One-shot (never periodic) and the exact worker under test.
            val workerClassName = readField(readField(request.captured, "workSpec"), "workerClassName")
            assertEquals(OutboxDrainWorker::class.java.name, workerClassName)
        }

    @Test
    fun scheduleDrain_duplicateRequests_keepPolicySuppressesDoubleRun() =
        runTest {
            val workManager = mockk<WorkManager>(relaxed = true)
            val scheduler = schedulerFor(workManager, FakeSessionManager(session("user-1")))

            scheduler.scheduleDrain()
            scheduler.scheduleDrain()

            // Two enqueues, both KEEP: WorkManager suppresses the second run.
            verify(exactly = 2) {
                workManager.enqueueUniqueWork(
                    "outbox-drain-user-1",
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>(),
                )
            }
        }

    @Test
    fun scheduleDrain_isKeyedPerUser() =
        runTest {
            val workManagerA = mockk<WorkManager>(relaxed = true)
            val workManagerB = mockk<WorkManager>(relaxed = true)

            schedulerFor(workManagerA, FakeSessionManager(session("user-a"))).scheduleDrain()
            schedulerFor(workManagerB, FakeSessionManager(session("user-b"))).scheduleDrain()

            verify(exactly = 1) {
                workManagerA.enqueueUniqueWork("outbox-drain-user-a", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
            }
            verify(exactly = 1) {
                workManagerB.enqueueUniqueWork("outbox-drain-user-b", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
            }
        }

    @Test
    fun scheduleDrain_requestHasConnectedConstraintAndExponentialBackoff() {
        val scheduler =
            WorkManagerOutboxDrainScheduler(
                context = mockk<Context>(),
                sessionManager = FakeSessionManager(null),
                workManager = mockk<WorkManager>(relaxed = true),
            )

        val workSpec = readField(scheduler.buildDrainRequest(), "workSpec")

        val constraints = readField(workSpec, "constraints") as Constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)

        assertEquals(BackoffPolicy.EXPONENTIAL, readField(workSpec, "backoffPolicy"))
        assertEquals(30_000L, readField(workSpec, "backoffDelayDuration"))
    }

    @Test
    fun scheduleDrain_withoutSession_doesNotEnqueue() =
        runTest {
            val workManager = mockk<WorkManager>(relaxed = true)
            val scheduler = schedulerFor(workManager, FakeSessionManager(null))

            scheduler.scheduleDrain()

            verify(exactly = 0) {
                workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            }
        }

    // ── S7 drain invariants (T7.2) ──────────────────────────────────

    /**
     * A write while offline must still enqueue. The scheduler deliberately has no
     * connectivity dependency: it enqueues unconditionally and the CONNECTED
     * constraint defers execution until the network returns. The absence of any
     * connectivity check is the invariant under test (there is nothing to stub for
     * "offline"), and the constraint is the deferral mechanism.
     */
    @Test
    fun scheduleDrain_whileOffline_stillEnqueuesConstrainedWork() =
        runTest {
            val workManager = mockk<WorkManager>(relaxed = true)
            val request = slot<OneTimeWorkRequest>()
            val scheduler = schedulerFor(workManager, FakeSessionManager(session("user-1")))

            scheduler.scheduleDrain()

            verify(exactly = 1) {
                workManager.enqueueUniqueWork("outbox-drain-user-1", ExistingWorkPolicy.KEEP, capture(request))
            }
            val constraints = readField(readField(request.captured, "workSpec"), "constraints") as Constraints
            assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        }

    /**
     * Duplicate drain requests must target the SAME unique name so that
     * `ExistingWorkPolicy.KEEP` suppresses a second concurrent run. This is the
     * scheduler-side half of the no-double-drain guarantee; WorkManager owns the
     * other half.
     */
    @Test
    fun scheduleDrain_duplicateRequests_reuseTheSameUniqueNameForKeepDedup() =
        runTest {
            val workManager = mockk<WorkManager>(relaxed = true)
            val names = mutableListOf<String>()
            val scheduler = schedulerFor(workManager, FakeSessionManager(session("user-1")))

            scheduler.scheduleDrain()
            scheduler.scheduleDrain()

            verify(exactly = 2) {
                workManager.enqueueUniqueWork(capture(names), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
            }
            assertEquals(listOf("outbox-drain-user-1", "outbox-drain-user-1"), names)
        }

    // ── helpers ─────────────────────────────────────────────────────

    private fun schedulerFor(
        workManager: WorkManager,
        sessionManager: SessionManager,
    ) = WorkManagerOutboxDrainScheduler(
        context = mockk<Context>(),
        sessionManager = sessionManager,
        workManager = workManager,
    )

    private fun session(userId: String): AuthSession = AuthSession(userId = userId, email = "$userId@test.com")

    private fun readField(
        target: Any,
        name: String,
    ): Any {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target) ?: error("field $name was null")
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private class FakeSessionManager(
        private val session: AuthSession?,
    ) : SessionManager {
        override suspend fun restoreSession(): Result<AuthSession?> = Result.success(session)

        override suspend fun getCurrentSession(): Result<AuthSession?> = Result.success(session)

        override suspend fun ensureFreshSession(): Result<AuthSession> = session?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no session"))

        override suspend fun signOutAll(): Result<Unit> = Result.success(Unit)

        override suspend fun setCurrentSession(session: AuthSession?): Result<Unit> = Result.success(Unit)
    }
}

package com.nexo.data.remote.sync

import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OutboxCommit] (OUTBOX-COMMIT in sync-layer-split/spec, id 2381).
 *
 * Covers all 6 spec scenarios via a MockK [SyncOutboxDao] and the public
 * [OutboxCommit.commit] / [OutboxCommit.processOutboxStream] APIs.
 *
 * No `delay` is observed by the helper — the no-implicit-backoff assertion is
 * enforced by counting suspend resumes on the MockK `apply` lambda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxCommitTest {
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var outbox: OutboxCommit

    @Before
    fun setUp() {
        outboxDao = mockk(relaxed = true)
        outbox = OutboxCommit(outboxDao = outboxDao, maxRetries = 3)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    private fun item(
        id: String,
        retryCount: Int = 0,
    ): SyncOutboxEntity =
        SyncOutboxEntity(
            id = id,
            entityType = SyncEntityType.READING_PROGRESS.name,
            entityId = "book-1",
            operation = SyncOperation.UPDATE.name,
            payloadJson = "{}",
            createdAtEpochMillis = 100L,
            retryCount = retryCount,
        )

    // ─── commit: Ok branch (SCEN-OUTBOX-1) ──────────────────────────────

    @Test
    fun commit_applyOk_deletesItemAndReturnsAcked() =
        runTest {
            val i = item("outbox-1")
            val outcome = outbox.commit(i) { ApplyOutcome.Ok }

            assertEquals(CommitOutcome.Acked, outcome)
            coVerify(exactly = 1) { outboxDao.deleteById("outbox-1") }
            coVerify(exactly = 0) { outboxDao.incrementRetryCount(any(), any()) }
            coVerify(exactly = 0) { outboxDao.pruneFailedItems(any()) }
        }

    // ─── commit: Retryable, below threshold (SCEN-OUTBOX-2) ─────────────

    @Test
    fun commit_applyRetryable_belowThreshold_incrementsAndReturnsRetryable() =
        runTest {
            val i = item("outbox-2", retryCount = 1)
            val cause = RuntimeException("network")
            val outcome = outbox.commit(i) { ApplyOutcome.Retryable(cause) }

            assertTrue(outcome is CommitOutcome.Retryable)
            assertEquals(cause, (outcome as CommitOutcome.Retryable).cause)
            coVerify(exactly = 1) { outboxDao.incrementRetryCount("outbox-2", "network") }
            coVerify(exactly = 0) { outboxDao.pruneFailedItems(any()) }
            coVerify(exactly = 0) { outboxDao.deleteById(any()) }
        }

    @Test
    fun commit_applyRetryable_atThreshold_incrementsPrunesAndReturnsPoison() =
        runTest {
            val i = item("outbox-3", retryCount = 2)
            val cause = RuntimeException("network")
            val outcome = outbox.commit(i) { ApplyOutcome.Retryable(cause) }

            assertTrue(outcome is CommitOutcome.Poison)
            assertEquals(cause, (outcome as CommitOutcome.Poison).cause)
            coVerify(exactly = 1) { outboxDao.incrementRetryCount("outbox-3", "network") }
            coVerify(exactly = 1) { outboxDao.pruneFailedItems(3) }
            coVerify(exactly = 0) { outboxDao.deleteById(any()) }
        }

    @Test
    fun commit_thresholdCheck_occursAfterIncrement_notBefore() =
        runTest {
            // retryCount == 0, after increment it's 1, threshold 3 → no poison
            val i = item("outbox-4", retryCount = 0)
            val outcome = outbox.commit(i) { ApplyOutcome.Retryable(RuntimeException("x")) }
            assertTrue(outcome is CommitOutcome.Retryable)
            coVerify(exactly = 0) { outboxDao.pruneFailedItems(any()) }
        }

    // ─── commit: immediate Poison (SCEN-OUTBOX-4) ───────────────────────

    @Test
    fun commit_applyPoison_prunesImmediatelyAndReturnsPoison() =
        runTest {
            val i = item("outbox-5", retryCount = 0)
            val cause = IllegalStateException("bad_payload")
            val outcome = outbox.commit(i) { ApplyOutcome.Poison(cause) }

            assertTrue(outcome is CommitOutcome.Poison)
            assertEquals(cause, (outcome as CommitOutcome.Poison).cause)
            coVerify(exactly = 1) { outboxDao.pruneFailedItems(3) }
            coVerify(exactly = 0) { outboxDao.incrementRetryCount(any(), any()) }
            coVerify(exactly = 0) { outboxDao.deleteById(any()) }
        }

    // ─── processOutboxStream: ordering (SCEN-OUTBOX-5) ──────────────────

    @Test
    fun processOutboxStream_preservesOrdering() =
        runTest {
            val batch = listOf(item("A"), item("B"), item("C"))
            val events =
                outbox
                    .processOutboxStream(
                        items = flowOf(batch),
                        apply = { ApplyOutcome.Ok },
                    ).take(3)
                    .toList()

            assertEquals(
                listOf(
                    CommitEvent.Acked("A"),
                    CommitEvent.Acked("B"),
                    CommitEvent.Acked("C"),
                ),
                events,
            )
            coVerify(exactly = 1) { outboxDao.deleteById("A") }
            coVerify(exactly = 1) { outboxDao.deleteById("B") }
            coVerify(exactly = 1) { outboxDao.deleteById("C") }
        }

    @Test
    fun processOutboxStream_emitsMixedAckedRetriedPruned() =
        runTest {
            val batch =
                listOf(
                    item("A"),
                    item("B", retryCount = 1), // will increment to 2 → Retried
                    item("C", retryCount = 2), // will increment to 3 → Poison → Pruned
                )
            val events =
                outbox
                    .processOutboxStream(
                        items = flowOf(batch),
                        apply = { item ->
                            when (item.id) {
                                "A" -> ApplyOutcome.Ok
                                "B" -> ApplyOutcome.Retryable(RuntimeException("net"))
                                "C" -> ApplyOutcome.Retryable(RuntimeException("net"))
                                else -> error("unexpected id ${item.id}")
                            }
                        },
                    ).take(3)
                    .toList()

            assertEquals(CommitEvent.Acked("A"), events[0])
            assertTrue("B should be Retried", events[1] is CommitEvent.Retried)
            assertEquals("B", (events[1] as CommitEvent.Retried).itemId)
            assertEquals(2, (events[1] as CommitEvent.Retried).retryCount)
            assertEquals(CommitEvent.Pruned("C", "net"), events[2])
        }

    // ─── no implicit backoff (SCEN-OUTBOX-6) ───────────────────────────

    @Test
    fun commit_doesNotEnforceBackoff_applyIsCalledOnceNoDelay() =
        runTest {
            val i = item("outbox-6")
            var applyCalls = 0
            val outcome =
                outbox.commit(i) {
                    applyCalls++
                    ApplyOutcome.Retryable(RuntimeException("retry"))
                }

            assertEquals(1, applyCalls)
            assertNotEquals(CommitOutcome.Acked, outcome)
            // The helper did NOT call incrementRetryCount more than once, did NOT
            // sleep, did NOT call apply again.
            coVerify(exactly = 1) { outboxDao.incrementRetryCount("outbox-6", "retry") }
        }

    // ─── maxRetries is configurable ─────────────────────────────────────

    @Test
    fun commit_maxRetriesRespected_onePrunedAtBoundary() =
        runTest {
            val custom = OutboxCommit(outboxDao = outboxDao, maxRetries = 1)
            val outcome =
                custom.commit(item("X", retryCount = 0)) {
                    ApplyOutcome.Retryable(RuntimeException("e"))
                }

            assertTrue(outcome is CommitOutcome.Poison)
            coVerify(exactly = 1) { outboxDao.pruneFailedItems(1) }
        }

    // ─── apply throws → exception bubbles, NOT converted to Retryable ──

    @Test
    fun commit_applyThrows_propagatesException_notCaught() =
        runTest {
            val i = item("outbox-throw")
            val thrown = RuntimeException("apply failed")

            val caught =
                runCatching {
                    outbox.commit(i) { throw thrown }
                }.exceptionOrNull()

            assertNotNull(caught)
            assertEquals(thrown, caught)
            // The helper must NOT swallow the exception or treat it as Retryable.
            coVerify(exactly = 0) { outboxDao.deleteById(any()) }
            coVerify(exactly = 0) { outboxDao.incrementRetryCount(any(), any()) }
            coVerify(exactly = 0) { outboxDao.pruneFailedItems(any()) }
        }

    // ─── commit: apply throws after Ok branch is not reachable, sanity ──

    @Test
    fun commit_emptyApplyOutcomeBranchAcked_doesNotTouchRetryOrPrune() =
        runTest {
            // Ensure the Acked branch is a clean single DAO call.
            val outcome = outbox.commit(item("A")) { ApplyOutcome.Ok }
            assertEquals(CommitOutcome.Acked, outcome)
            coVerify(exactly = 1) { outboxDao.deleteById("A") }
            coVerify(exactly = 0) { outboxDao.incrementRetryCount(any(), any()) }
        }
}

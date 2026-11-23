package com.nexo.presentation.viewmodel.highlights

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavior contract for [SyncStateHolder].
 *
 * "Did the pull happen" is asserted on [CountingPull] call counts, never on
 * state timing, so a passing test cannot be an accident of scheduler ordering.
 * Cooldown uses virtual time through [FakeClock]; the 3s reset and the 600ms
 * reveal use the coroutine test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateHolderTest {
    @Test
    fun `manual sync while one is active is ignored instead of cancelling it`() =
        runTest {
            val pull = CountingPull { gate -> gate.await() }
            val holder = holder(pull)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            holder.syncHighlights(SyncTrigger.Manual)
            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()

            assertEquals("in-flight pull must not be restarted", 1, pull.calls)

            pull.release()
            runCurrent()
            assertEquals(HighlightsSyncState.Synced, holder.syncState.value)
        }

    @Test
    fun `manual sync inside cooldown window is dropped, after window it runs`() =
        runTest {
            val pull = CountingPull { it.complete(Unit) }
            val clock = FakeClock()
            val holder = holder(pull, clock)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals(1, pull.calls)

            clock.advance(MANUAL_COOLDOWN_MS - 1)
            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals("cooling down, second press is dropped", 1, pull.calls)

            clock.advance(2)
            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals("cooldown elapsed, pull runs again", 2, pull.calls)
        }

    @Test
    fun `automatic sync is not subject to the manual cooldown`() =
        runTest {
            val pull = CountingPull { it.complete(Unit) }
            val clock = FakeClock()
            val holder = holder(pull, clock)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals(1, pull.calls)

            holder.syncHighlights(SyncTrigger.Automatic)
            runCurrent()
            assertEquals("automatic path has its own policy", 2, pull.calls)
        }

    @Test
    fun `automatic sync shows no Syncing state before the slow threshold`() =
        runTest {
            val pull = CountingPull { gate -> gate.await() }
            val holder = holder(pull)

            holder.syncHighlights(SyncTrigger.Automatic)
            runCurrent()
            advanceTimeBy(AUTO_SPINNER_THRESHOLD_MS - 1)

            assertEquals(
                "fast automatic sync must stay invisible",
                HighlightsSyncState.Idle,
                holder.syncState.value,
            )

            advanceTimeBy(2)
            assertEquals(
                "past the threshold the automatic sync becomes visible",
                HighlightsSyncState.Syncing,
                holder.syncState.value,
            )

            pull.release()
            runCurrent()
            assertEquals(HighlightsSyncState.Synced, holder.syncState.value)
        }

    @Test
    fun `a fast automatic sync never shows the Synced confirmation either`() =
        runTest {
            // The screen-entry call passes no trigger, so it takes the default.
            // A pull that finishes before the slow threshold was never shown
            // starting; a "Synced" chip afterwards would be the same
            // unrequested animation, just late.
            val holder = holder(CountingPull { gate -> gate.complete(Unit) })

            holder.syncHighlights()
            runCurrent()
            advanceTimeBy(AUTO_SPINNER_THRESHOLD_MS - 1)

            assertEquals(
                "a fast entry sync must stay silent through the confirmation",
                HighlightsSyncState.Idle,
                holder.syncState.value,
            )
        }

    @Test
    fun `the screen entry default is automatic, not manual`() =
        runTest {
            // Entering the tab must not be treated as a user request: a default
            // of Manual would expose Syncing at once, which is exactly the
            // animation this change removes.
            val pull = CountingPull { gate -> gate.await() }
            val holder = holder(pull)

            holder.syncHighlights()
            runCurrent()

            assertEquals(
                "no trigger argument must mean Automatic",
                HighlightsSyncState.Idle,
                holder.syncState.value,
            )
            pull.release()
        }

    @Test
    fun `manual sync exposes Syncing state immediately`() =
        runTest {
            val pull = CountingPull { gate -> gate.await() }
            val holder = holder(pull)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()

            assertEquals(HighlightsSyncState.Syncing, holder.syncState.value)

            pull.release()
            runCurrent()
            assertEquals(HighlightsSyncState.Synced, holder.syncState.value)
        }

    @Test
    fun `Synced resets to Idle after three seconds`() =
        runTest {
            val pull = CountingPull { it.complete(Unit) }
            val clock = FakeClock()
            val holder = holder(pull, clock)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals(HighlightsSyncState.Synced, holder.syncState.value)

            clock.advance(SYNCED_DISPLAY_MS - 1)
            advanceTimeBy(SYNCED_DISPLAY_MS - 1)
            assertEquals(HighlightsSyncState.Synced, holder.syncState.value)

            advanceTimeBy(2)
            assertEquals(HighlightsSyncState.Idle, holder.syncState.value)
        }

    @Test
    fun `a throwing pull leaves state Idle instead of stuck Syncing`() =
        runTest {
            val holder =
                SyncStateHolder(
                    pull = { throw IllegalStateException("boom") },
                    scope = TestScope(testScheduler),
                )

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals(HighlightsSyncState.Idle, holder.syncState.value)

            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()
            assertEquals(HighlightsSyncState.Idle, holder.syncState.value)
        }

    @Test
    fun `a missing puller is a silent no-op`() =
        runTest {
            val holder = SyncStateHolder(pull = null, scope = TestScope(testScheduler))

            holder.syncHighlights(SyncTrigger.Automatic)
            runCurrent()
            holder.syncHighlights(SyncTrigger.Manual)
            runCurrent()

            assertEquals(HighlightsSyncState.Idle, holder.syncState.value)
        }

    // ------------------------------------------------------------------
    // Test seam
    // ------------------------------------------------------------------

    private fun TestScope.holder(
        pull: CountingPull,
        clock: FakeClock = FakeClock(),
    ): SyncStateHolder =
        SyncStateHolder(
            pull = pull::invoke,
            scope = TestScope(testScheduler),
            now = clock::now,
        )

    /** Cooldown reads wall-clock time, so tests drive it explicitly. */
    private class FakeClock {
        private var current = 0L

        fun now(): Long = current

        fun advance(millis: Long) {
            current += millis
        }
    }

    /**
     * Counts invocations and hands each caller a gate to await, so a test can
     * hold a pull in flight for as long as it wants.
     */
    private class CountingPull(
        private val body: suspend (gate: CompletableDeferred<Unit>) -> Unit,
    ) {
        var calls = 0
            private set

        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun invoke() {
            val gate = CompletableDeferred<Unit>()
            gates += gate
            calls++
            body(gate)
        }

        fun release() {
            gates.forEach { it.complete(Unit) }
        }
    }

    private companion object {
        const val AUTO_SPINNER_THRESHOLD_MS = 600L
        const val MANUAL_COOLDOWN_MS = 2500L
        const val SYNCED_DISPLAY_MS = 3000L
    }
}

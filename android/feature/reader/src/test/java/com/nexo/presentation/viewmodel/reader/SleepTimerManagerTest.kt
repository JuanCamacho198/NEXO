package com.nexo.presentation.viewmodel.reader

import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Timer Creation ──────────────────────────────────────────────

    @Test
    fun `startTimer with normal minutes starts countdown`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(5)

            val state = manager.state.value
            assertTrue("Timer should be active", state.isActive)
            assertFalse("Not end-of-chapter mode", state.isEndOfChapter)
            assertEquals("Should be 5 min", 5 * 60, state.remainingSecs)
            assertEquals("Preset should be 5", 5, state.presetMinutes)
            assertFalse("Not finished", state.isFinished)
        }

    @Test
    fun `startTimer with END_OF_CHAPTER activates end-of-chapter mode`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(SleepTimerManager.END_OF_CHAPTER)

            val state = manager.state.value
            assertTrue("Timer should be active", state.isActive)
            assertTrue("End-of-chapter mode should be active", state.isEndOfChapter)
            assertEquals("No countdown in end-of-chapter mode", 0, state.remainingSecs)
            assertNull("No preset in EOC mode", state.presetMinutes)
            assertFalse("Not finished", state.isFinished)
        }

    @Test
    fun `startTimer with zero is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(0)

            val state = manager.state.value
            assertFalse("Should remain idle", state.isActive)
            assertEquals("Remaining should be 0", 0, state.remainingSecs)
        }

    @Test
    fun `startTimer with negative value (non-EOC) is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(-5)

            val state = manager.state.value
            assertFalse("Should remain idle", state.isActive)
        }

    // ── Countdown ────────────────────────────────────────────────────

    @Test
    fun `startTimer sets remainingSecs correctly`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(1)
            assertEquals("1 min = 60 seconds", 60, manager.state.value.remainingSecs)

            manager.startTimer(5)
            assertEquals("5 min = 300 seconds", 300, manager.state.value.remainingSecs)
        }

    @Test
    fun `countdown advances to finished state`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val manager = SleepTimerManager(scope)

            manager.startTimer(1)
            assertTrue("Timer should be active", manager.state.value.isActive)

            // Advance time until all pending tasks complete
            scheduler.advanceUntilIdle()

            assertFalse("Timer should not be active after finishing", manager.state.value.isActive)
            assertTrue("Timer should be finished", manager.state.value.isFinished)
            assertEquals("Remaining should be 0", 0, manager.state.value.remainingSecs)
            assertNull("Preset should be null after finish", manager.state.value.presetMinutes)
            assertFalse("EOC should be false after finish", manager.state.value.isEndOfChapter)

            scope.cancel()
        }

    // ── End-of-Chapter ───────────────────────────────────────────────

    @Test
    fun `onChapterChanged in EOC mode finishes timer`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(SleepTimerManager.END_OF_CHAPTER)
            assertTrue("Timer should be active before chapter change", manager.state.value.isActive)

            manager.onChapterChanged()

            val state = manager.state.value
            assertFalse("Timer should be inactive after chapter change", state.isActive)
            assertTrue("Finished should be set after chapter change", state.isFinished)
            assertFalse("EOC mode should be reset after chapter change", state.isEndOfChapter)
        }

    @Test
    fun `onChapterChanged while not in EOC mode is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(5)
            manager.onChapterChanged()

            val state = manager.state.value
            assertTrue("Timer should still be active", state.isActive)
            assertFalse("Should not be finished", state.isFinished)
        }

    @Test
    fun `onChapterChanged while idle is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.onChapterChanged()

            val state = manager.state.value
            assertFalse("Should remain idle", state.isActive)
            assertFalse("Should not be finished", state.isFinished)
        }

    // ── Cancel & Dismiss ────────────────────────────────────────────

    @Test
    fun `cancel resets all timer state`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(SleepTimerManager.END_OF_CHAPTER)
            assertTrue("Timer should be active", manager.state.value.isActive)

            manager.cancel()

            val state = manager.state.value
            assertFalse("Timer should be cancelled", state.isActive)
            assertFalse("EOC mode should be reset", state.isEndOfChapter)
            assertFalse("Finished should not be set", state.isFinished)
            assertEquals("Remaining should be 0", 0, state.remainingSecs)
            assertNull("Preset should be null", state.presetMinutes)
        }

    @Test
    fun `cancel while idle is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.cancel()

            val state = manager.state.value
            assertFalse("Should remain idle", state.isActive)
            assertEquals("Remaining should be 0", 0, state.remainingSecs)
            assertNull("Preset should be null", state.presetMinutes)
        }

    @Test
    fun `dismissOverlay clears finished flag`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(SleepTimerManager.END_OF_CHAPTER)
            manager.onChapterChanged()
            assertTrue("Finished should be set after EOC trigger", manager.state.value.isFinished)

            manager.dismissOverlay()

            assertFalse("Finished should be cleared after dismiss", manager.state.value.isFinished)
        }

    @Test
    fun `dismissOverlay while not finished is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(5)
            manager.dismissOverlay()

            val state = manager.state.value
            assertTrue("Timer should still be active", state.isActive)
        }

    @Test
    fun `dismissOverlay while idle is no-op`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.dismissOverlay()

            val state = manager.state.value
            assertFalse("Should remain idle", state.isActive)
        }

    // ── Restart ──────────────────────────────────────────────────────

    @Test
    fun `startTimer while already counting restarts timer`() =
        runTest {
            val manager = createManager(testScheduler)

            manager.startTimer(10)
            assertTrue("Timer should be active", manager.state.value.isActive)
            assertEquals("Should be 10 min", 10 * 60, manager.state.value.remainingSecs)

            manager.startTimer(5)
            assertEquals("Should be 5 min after restart", 5 * 60, manager.state.value.remainingSecs)
            assertEquals("Preset should be 5", 5, manager.state.value.presetMinutes)
        }

    @Test
    fun `restart cancels previous countdown`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val manager = SleepTimerManager(scope)

            manager.startTimer(1)

            scheduler.advanceTimeBy(30_000L)
            scheduler.runCurrent()
            assertEquals("30 seconds remaining", 30, manager.state.value.remainingSecs)

            manager.startTimer(2)
            assertEquals("Should reset to 2 minutes", 2 * 60, manager.state.value.remainingSecs)

            scope.cancel()
        }

    // ── Formatting ───────────────────────────────────────────────────

    @Test
    fun `formatRemaining formats correctly`() =
        runTest {
            val manager = createManager(testScheduler)

            assertEquals("0:00", manager.formatRemaining(0))
            assertEquals("0:05", manager.formatRemaining(5))
            assertEquals("1:00", manager.formatRemaining(60))
            assertEquals("5:30", manager.formatRemaining(330))
            assertEquals("10:00", manager.formatRemaining(600))
            assertEquals("61:01", manager.formatRemaining(3661))
        }

    @Test
    fun `formatRemaining with negative returns 0-00`() =
        runTest {
            val manager = createManager(testScheduler)

            assertEquals("0:00", manager.formatRemaining(-1))
            assertEquals("0:00", manager.formatRemaining(-100))
        }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Test
    fun `timer job cancelled when scope is cancelled`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val manager = SleepTimerManager(scope)

            manager.startTimer(5)
            assertTrue("Timer should be active", manager.state.value.isActive)

            scope.cancel()

            scheduler.advanceTimeBy(10_000L)

            val state = manager.state.value
            assertEquals("Remaining should be unchanged", 5 * 60, state.remainingSecs)
        }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun createManager(scheduler: TestCoroutineScheduler): SleepTimerManager {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        return SleepTimerManager(scope)
    }
}

package com.nexo.data.remote.sync

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the settle gate: a non-active aggregate resolves `true` immediately,
 * an active aggregate times out to `false`, and a transition to settled resolves
 * `true` before the timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorSettleGateTest {
    @Test
    fun awaitSettled_nonActiveState_returnsTrue() =
        runTest {
            val orchestrator = mockk<SyncOrchestrator>()
            every { orchestrator.state } returns MutableStateFlow<SyncState>(SyncState.Idle)

            assertTrue(SyncOrchestratorSettleGate(orchestrator).awaitSettled())
        }

    @Test
    fun awaitSettled_activeState_timesOut_returnsFalse() =
        runTest {
            val orchestrator = mockk<SyncOrchestrator>()
            every { orchestrator.state } returns MutableStateFlow<SyncState>(SyncState.Active)

            assertFalse(
                SyncOrchestratorSettleGate(orchestrator, timeoutMillis = 1_000L).awaitSettled(),
            )
        }

    @Test
    fun awaitSettled_activeThenIdle_returnsTrue() =
        runTest {
            val state = MutableStateFlow<SyncState>(SyncState.Active)
            val orchestrator = mockk<SyncOrchestrator>()
            every { orchestrator.state } returns state
            val gate = SyncOrchestratorSettleGate(orchestrator, timeoutMillis = 60_000L)

            val settled = async { gate.awaitSettled() }
            runCurrent()
            state.value = SyncState.Idle

            assertTrue(settled.await())
        }
}

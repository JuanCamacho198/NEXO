package com.nexo.presentation.viewmodel

import com.nexo.presentation.UiEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery contract for the reader's one-shot UI events.
 *
 * The reader's `_uiEvent` is written by NON-suspending callbacks — the holder
 * `onEvent` / `onErrorEvent` lambdas call `tryEmit`. With a zero-capacity
 * `MutableSharedFlow` that returns `false` and **silently drops** the event
 * whenever no collector is *suspended inside `collect`* at that instant. That
 * is how the clipboard confirmation vanished in the immersive reader: the
 * event was emitted, the screen was collecting, and the user still saw nothing.
 *
 * [READER_UI_EVENT_BUFFER_CAPACITY] is the production value asserted here, so
 * shrinking it back to zero fails this test instead of silently killing every
 * reader snackbar again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelUiEventTest {
    /** Mirrors the reader's `MutableSharedFlow<UiEvent>(extraBufferCapacity = …)`. */
    private val uiEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = READER_UI_EVENT_BUFFER_CAPACITY)

    @Test
    fun `a buffered emit is accepted instead of silently dropped`() {
        // Emitting while nothing is suspended in `collect` is exactly the state
        // the reader is in when a holder callback fires from a background
        // coroutine. The buffer must absorb it.
        assertTrue(
            "A zero-capacity SharedFlow returns false here and the reader shows no feedback",
            uiEvent.tryEmit(UiEvent.ShowToast("No text selected")),
        )
        assertTrue(uiEvent.tryEmit(UiEvent.ShowSnackbar("Copiado al portapapeles")))
    }

    @Test
    fun `a snackbar reaches an active subscriber unchanged`() =
        runTest {
            val received = mutableListOf<UiEvent>()
            val collector = launch { uiEvent.collect { received += it } }
            advanceUntilIdle()

            assertTrue(uiEvent.tryEmit(UiEvent.ShowSnackbar("Copiado al portapapeles")))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, received.size)
            assertEquals(
                "Copiado al portapapeles",
                (received.single() as UiEvent.ShowSnackbar).message,
            )
        }
}

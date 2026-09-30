package com.nexo.debug

import com.nexo.testutil.MainDispatcherRule
import io.sentry.protocol.SentryId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * PR4 / task 4.3+4.4 — pure-JVM test of [FeedbackQueue] + [FeedbackViewModel].
 *
 * Coverage:
 * - Queue FIFO + cap 25 (oldest dropped, newest kept)
 * - Dismiss-once idempotence (mark twice, single row)
 * - Counter clamp at MAX_CHARS
 * - Online submit transitions Editing → Sent
 * - Offline submit (capture returns null) enqueues + transitions to Error
 * - flushQueue sends entries in FIFO order; failures stay queued
 * - Empty-text submit is allowed (spec D3)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackQueueTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Sentry requires eventIds to be hex UUIDs (32 chars no dashes or 36 with).
     * Generate one per test so the [SentryId] constructor in [FeedbackCapture]
     * doesn't throw on validation. Suffixes are hex-only so the result is
     * still a valid SentryId format.
     */
    private fun newEventId(suffix: String = ""): String {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val hex = if (suffix.isBlank()) "" else suffix.filter { it.isDigit() || it in 'a'..'f' }
        return if (hex.isBlank()) uuid else "${uuid.substring(0, 32 - hex.length)}$hex"
    }

    private fun entry(
        eventId: String = newEventId(),
        text: String = "hello",
    ) = FeedbackEvent.FeedbackEntry(
        eventId = eventId,
        text = text,
        timestamp = System.currentTimeMillis(),
        bookId = "book-1",
        chapterIndex = 3,
        page = 142,
        title = "La Odisea",
        chapterLabel = "Capítulo IX",
    )

    // ── Queue FIFO + cap ──────────────────────────────────────────────────

    @Test
    fun enqueue_underCap_preservesOrder() {
        val q = FeedbackQueue()
        val id1 = newEventId("-a")
        val id2 = newEventId("-b")
        val id3 = newEventId("-c")
        q.enqueue(entry(eventId = id1, text = "first"))
        q.enqueue(entry(eventId = id2, text = "second"))
        q.enqueue(entry(eventId = id3, text = "third"))

        val snapshot = q.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(id1, snapshot[0].eventId)
        assertEquals(id2, snapshot[1].eventId)
        assertEquals(id3, snapshot[2].eventId)
    }

    @Test
    fun enqueue_overCap_dropsOldest() {
        val q = FeedbackQueue()
        val ids = (0 until FeedbackQueue.QUEUE_CAPACITY + 5).map { newEventId("-$it") }
        ids.forEach { q.enqueue(entry(eventId = it)) }
        assertEquals(FeedbackQueue.QUEUE_CAPACITY, q.size())
        val kept = q.snapshot().map { it.eventId }
        // The first 5 entries (ids[0]..ids[4]) should be dropped; the rest preserved.
        assertEquals(ids[5], kept.first())
        assertEquals(ids.last(), kept.last())
    }

    @Test
    fun enqueue_returnsDropped_whenFull() {
        val q = FeedbackQueue()
        val ids = (0 until FeedbackQueue.QUEUE_CAPACITY).map { newEventId("-$it") }
        ids.forEach { q.enqueue(entry(eventId = it)) }
        val newestId = newEventId("-new")
        val dropped = q.enqueue(entry(eventId = newestId))
        assertEquals(ids[0], dropped?.eventId)
        assertEquals(FeedbackQueue.QUEUE_CAPACITY, q.size())
    }

    @Test
    fun popFirst_returnsNull_whenEmpty() {
        val q = FeedbackQueue()
        assertNull(q.popFirst())
        val id = newEventId("-pop")
        q.enqueue(entry(eventId = id))
        assertEquals(id, q.popFirst()?.eventId)
        assertNull(q.popFirst())
    }

    @Test
    fun clearQueue_dropsAll() {
        val q = FeedbackQueue()
        repeat(3) { q.enqueue(entry(eventId = newEventId("-$it"))) }
        q.clearQueue()
        assertEquals(0, q.size())
        assertTrue(q.snapshot().isEmpty())
    }

    // ── Dismiss-once ──────────────────────────────────────────────────────

    @Test
    fun markDismissed_isIdempotent() {
        val q = FeedbackQueue()
        val id = newEventId("-dismiss")
        q.markDismissed(id)
        q.markDismissed(id)
        q.markDismissed(id)
        assertEquals(1, q.dismissedSnapshot().size)
        assertTrue(q.isDismissed(id))
    }

    @Test
    fun markDismissed_separateIds_areDistinct() {
        val q = FeedbackQueue()
        q.markDismissed(newEventId("-d1"))
        q.markDismissed(newEventId("-d2"))
        assertEquals(2, q.dismissedSnapshot().size)
    }

    @Test
    fun isDismissed_initiallyFalse() {
        val q = FeedbackQueue()
        assertFalse(q.isDismissed(newEventId("-x")))
    }

    // ── ViewModel state machine ───────────────────────────────────────────

    @Test
    fun viewModel_init_idle_whenNoEventId() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = null,
            )
        assertEquals(FeedbackEvent.FeedbackSheetState.Idle, vm.state.value)
    }

    @Test
    fun viewModel_init_editing_whenEventIdPresent() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-evt"),
            )
        val state = vm.state.value
        assertTrue("expected Editing, got $state", state is FeedbackEvent.FeedbackSheetState.Editing)
    }

    @Test
    fun viewModel_init_idle_whenEventIdDismissed() {
        val dismissedId = newEventId("-dismissed")
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = setOf(dismissedId),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = dismissedId,
            )
        assertEquals(FeedbackEvent.FeedbackSheetState.Idle, vm.state.value)
    }

    @Test
    fun viewModel_onTextChanged_clampsAt240() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-clamp"),
            )
        val huge = "a".repeat(500)
        vm.onTextChanged(huge)
        val state = vm.state.value as FeedbackEvent.FeedbackSheetState.Editing
        assertEquals(FeedbackViewModel.MAX_CHARS, state.text.length)
        assertEquals(FeedbackViewModel.MAX_CHARS, vm.charCount.value)
    }

    @Test
    fun viewModel_onTextChanged_updatesCounterLive() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-counter"),
            )
        vm.onTextChanged("hi")
        assertEquals(2, vm.charCount.value)
        vm.onTextChanged("hello world")
        assertEquals(11, vm.charCount.value)
    }

    @Test
    fun viewModel_submit_onlineTransitionsToSent() {
        val sentIds = mutableListOf<String>()
        val eventId = newEventId("-online")
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = eventId,
                captureFn = { entry ->
                    sentIds += entry.eventId
                    SentryId(entry.eventId)
                },
            )
        vm.onTextChanged("great feedback")
        vm.submit()
        assertEquals(FeedbackEvent.FeedbackSheetState.Sent, vm.state.value)
        assertEquals(listOf(eventId), sentIds)
        assertEquals(0, vm.queuedCount())
    }

    @Test
    fun viewModel_submit_emptyTextAllowed() {
        val sentIds = mutableListOf<String>()
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-empty"),
                captureFn = { entry ->
                    sentIds += entry.eventId
                    SentryId(entry.eventId)
                },
            )
        // Do not call onTextChanged — empty text.
        vm.submit()
        assertEquals(FeedbackEvent.FeedbackSheetState.Sent, vm.state.value)
        assertEquals(1, sentIds.size)
    }

    @Test
    fun viewModel_submit_offlineEnqueuesAndTransitionsToError() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-offline"),
                captureFn = { null }, // Sentry uninit / offline
            )
        vm.onTextChanged("offline feedback")
        vm.submit()
        val state = vm.state.value
        assertTrue("expected Error, got $state", state is FeedbackEvent.FeedbackSheetState.Error)
        assertEquals(1, vm.queuedCount())
        // Counter still 16 ("offline feedback".length) so the user can keep editing.
        assertEquals(16, vm.charCount.value)
    }

    @Test
    fun viewModel_submit_captureThrows_enqueuesAndTransitionsToError() {
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = newEventId("-throw"),
                captureFn = { throw IllegalStateException("boom") },
            )
        vm.onTextChanged("throw")
        vm.submit()
        assertTrue(vm.state.value is FeedbackEvent.FeedbackSheetState.Error)
        assertEquals(1, vm.queuedCount())
    }

    @Test
    fun viewModel_dismiss_isIdempotent() {
        val dismissedId = newEventId("-dismiss")
        val vm =
            FeedbackViewModel(
                initialQueue = emptyList(),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = dismissedId,
            )
        vm.dismiss()
        vm.dismiss()
        vm.dismiss()
        val (_, dismissed) = vm.exportForPersistence()
        assertEquals(1, dismissed.size)
        assertTrue(dismissedId in dismissed)
    }

    @Test
    fun viewModel_flushQueue_sendsInOrder_stopsOnFailure() {
        val sentIds = mutableListOf<String>()
        val id1 = newEventId("-q1")
        val id2 = newEventId("-q2")
        val id3 = newEventId("-q3")
        val vm =
            FeedbackViewModel(
                initialQueue =
                    listOf(
                        entry(eventId = id1),
                        entry(eventId = id2),
                        entry(eventId = id3),
                    ),
                initialDismissed = emptySet(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = null,
                captureFn = { entry ->
                    if (entry.eventId == id2) {
                        null
                    } else {
                        sentIds += entry.eventId
                        SentryId(entry.eventId)
                    }
                },
            )
        val flushed = vm.flushQueue()
        // id1 succeeds, id2 fails (re-enqueued at end), id3 never attempted.
        assertEquals(1, flushed)
        assertEquals(listOf(id1), sentIds)
        assertEquals(2, vm.queuedCount())
        // After re-enqueue, id2 lands at the END of the queue (id3 is still first).
        val remaining = vm.queueStateForTest().map { it.eventId }
        assertEquals(listOf(id3, id2), remaining)
    }

    @Test
    fun viewModel_export_roundTrips() {
        val queuedId = newEventId("-q1")
        val dismissedId = newEventId("-dismissed")
        val q =
            FeedbackQueue(
                initialQueue = listOf(entry(eventId = queuedId)),
                initialDismissed = setOf(dismissedId),
            )
        val vm =
            FeedbackViewModel(
                initialQueue = q.snapshot(),
                initialDismissed = q.dismissedSnapshot(),
                initialBook = FeedbackEvent.BookMeta("b", "Title", "Cap", 0, 1),
                initialEventId = dismissedId,
            )
        val (queue, dismissed) = vm.exportForPersistence()
        assertEquals(1, queue.size)
        assertEquals(queuedId, queue[0].eventId)
        assertEquals(setOf(dismissedId), dismissed)
    }
}

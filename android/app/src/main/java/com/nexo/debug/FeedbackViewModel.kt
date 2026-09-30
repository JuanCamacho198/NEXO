package com.nexo.debug

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.sentry.protocol.SentryId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the crash-feedback sheet (PR4 / tasks 4.3-4.4).
 *
 * State machine mirror of the desktop dialog (spec D3):
 *   Idle → Editing → Sending → Sent | Error
 *
 * Counter: mobile limit is 240 chars (vs 500 desktop); the live counter is
 * the source of truth for `value.length`, the UI just observes it.
 *
 * Dismiss-once: marking dismissed is idempotent — the ViewModel delegates to
 * [FeedbackQueue.markDismissed], which uses a Set under the hood.
 *
 * Offline path: when Sentry capture returns null (uninit SDK or capture
 * throws), the entry is appended to the queue and flushed on the next
 * successful submit / explicit `flushQueue` call.
 *
 * The ViewModel is pure-Kotlin (no Android imports except `ViewModel`); tests
 * drive it through [MainDispatcherRule] + mocked [FeedbackCapture].
 */
class FeedbackViewModel(
    private val initialQueue: List<FeedbackEvent.FeedbackEntry>,
    private val initialDismissed: Set<String>,
    private val initialBook: FeedbackEvent.BookMeta,
    private val initialEventId: String?,
    private val captureFn: (FeedbackEvent.FeedbackEntry) -> SentryId? = ::defaultCapture,
    private val maxChars: Int = MAX_CHARS,
) : ViewModel() {
    private val queue: FeedbackQueue = FeedbackQueue(initialQueue, initialDismissed)

    private val _state =
        MutableStateFlow<FeedbackEvent.FeedbackSheetState>(
            if (initialEventId != null && !queue.isDismissed(initialEventId)) {
                FeedbackEvent.FeedbackSheetState.Editing("")
            } else {
                FeedbackEvent.FeedbackSheetState.Idle
            },
        )
    val state: StateFlow<FeedbackEvent.FeedbackSheetState> = _state.asStateFlow()

    /** Live counter — exposed so the UI binds `n / 240` from a single source. */
    val charCount: StateFlow<Int> =
        MutableStateFlow(0)
            .also { counter ->
                // Mirror the editing state's text length into the counter so tests can
                // observe it without spinning up Compose.
                viewModelScope.launch {
                    _state.collect { s ->
                        if (s is FeedbackEvent.FeedbackSheetState.Editing) {
                            counter.value = s.text.length
                        }
                    }
                }
            }.asStateFlow()

    /** The book context the sheet should display (auto-context card). */
    val bookMeta: FeedbackEvent.BookMeta = initialBook

    /** The eventId the sheet should link feedback to (or null when unknown). */
    val eventId: String? = initialEventId

    /** Whether the user is allowed to submit at this moment. */
    val canSubmit: StateFlow<Boolean> =
        MutableStateFlow(false)
            .also { gate ->
                viewModelScope.launch {
                    _state.collect { s ->
                        gate.value = s is FeedbackEvent.FeedbackSheetState.Editing
                    }
                }
            }.asStateFlow()

    /** Queue size — exposed so the ViewModel can decide whether to auto-flush. */
    fun queuedCount(): Int = queue.size()

    /** Append a character to the editing text. Caps at [maxChars]. */
    fun onTextChanged(next: String) {
        val clamped = if (next.length > maxChars) next.substring(0, maxChars) else next
        _state.value = FeedbackEvent.FeedbackSheetState.Editing(clamped)
    }

    /**
     * Submit the current editing text.
     *
     * Empty text is allowed (spec D3 — empty submit MUST still send).
     * Sentry capture returning null ⇒ entry is queued for later flush.
     * Returns the new state for test assertions.
     */
    fun submit(): FeedbackEvent.FeedbackSheetState {
        val editing =
            _state.value as? FeedbackEvent.FeedbackSheetState.Editing
                ?: return _state.value
        _state.value = FeedbackEvent.FeedbackSheetState.Sending
        val entry =
            FeedbackEvent.FeedbackEntry(
                eventId = initialEventId ?: "",
                text = editing.text,
                timestamp = System.currentTimeMillis(),
                bookId = initialBook.bookId,
                chapterIndex = initialBook.chapterIndex,
                page = initialBook.page,
                title = initialBook.title,
                chapterLabel = initialBook.chapterLabel,
            )
        return try {
            val sentId = captureFn(entry)
            if (sentId != null) {
                _state.value = FeedbackEvent.FeedbackSheetState.Sent
            } else {
                queue.enqueue(entry)
                _state.update { FeedbackEvent.FeedbackSheetState.Error(QUEUE_FALLBACK_MESSAGE) }
            }
            _state.value
        } catch (t: Throwable) {
            queue.enqueue(entry)
            _state.update { FeedbackEvent.FeedbackSheetState.Error(t.message ?: QUEUE_FALLBACK_MESSAGE) }
            _state.value
        }
    }

    /**
     * Mark the current eventId as dismissed and transition to Idle. Idempotent
     * — calling twice leaves the dismissed set unchanged (spec D3).
     */
    fun dismiss() {
        initialEventId?.let { queue.markDismissed(it) }
        _state.value = FeedbackEvent.FeedbackSheetState.Idle
    }

    /**
     * Flush the queued entries via [captureFn]. Returns the number of entries
     * successfully sent. Failures stay in the queue.
     */
    fun flushQueue(): Int {
        var sent = 0
        while (true) {
            val head = queue.popFirst() ?: break
            val id = runCatching { captureFn(head) }.getOrNull()
            if (id != null) {
                sent++
            } else {
                // Re-enqueue and stop — head failed, retry later.
                queue.enqueue(head)
                break
            }
        }
        return sent
    }

    /** Snapshot the current queue + dismissed sets for persistence write-back. */
    fun exportForPersistence(): Pair<List<FeedbackEvent.FeedbackEntry>, Set<String>> = queue.export()

    @VisibleForTesting
    internal fun queueStateForTest(): List<FeedbackEvent.FeedbackEntry> = queue.snapshot()

    companion object {
        /**
         * Mobile char cap from the feedback-design contract #2460 (njdtk).
         * Desktop uses 500; mobile is 240 by design.
         */
        const val MAX_CHARS = 240

        const val QUEUE_FALLBACK_MESSAGE = "queued_offline"

        /**
         * Default capture function: delegates to [FeedbackCapture.submit].
         * Injected as a seam so tests can swap in a mock without touching
         * the Sentry SDK directly.
         */
        private fun defaultCapture(entry: FeedbackEvent.FeedbackEntry): SentryId? = FeedbackCapture.submit(entry)

        /**
         * Factory — Compose-style. The Activity/Application owns persistence
         * and is responsible for hydrating the initial queue + dismissed
         * sets from [FeedbackPersistence] before constructing the ViewModel.
         */
        fun factory(
            initialQueue: List<FeedbackEvent.FeedbackEntry>,
            initialDismissed: Set<String>,
            book: FeedbackEvent.BookMeta,
            eventId: String?,
            captureFn: (FeedbackEvent.FeedbackEntry) -> SentryId? = ::defaultCapture,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FeedbackViewModel(
                        initialQueue = initialQueue,
                        initialDismissed = initialDismissed,
                        initialBook = book,
                        initialEventId = eventId,
                        captureFn = captureFn,
                    ) as T
            }
    }
}

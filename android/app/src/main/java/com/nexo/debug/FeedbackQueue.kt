package com.nexo.debug

/**
 * Pure FIFO queue + dismissed-set logic for the crash-feedback flow.
 *
 * No Android types: this class is the testable core. The persistence layer
 * ([FeedbackPersistence]) just round-trips JSON; FIFO + cap + idempotency
 * are enforced here so the tests can drive state without Robolectric.
 *
 * Invariants (spec D3 — submit/dismiss/offline):
 * - Queue: FIFO with cap [QUEUE_CAPACITY] (25). Enqueue when full drops the
 *   oldest entry (newest-feedback-wins — feedback is high-signal even when
 *   queued offline, so dropping oldest is acceptable).
 * - Dismissed: idempotent set. Add twice = single row (dismiss-once contract).
 * - Empty text is allowed (spec D3 — empty submit MUST still send).
 *
 * The class is intentionally not thread-safe: the ViewModel drives mutations
 * on the main dispatcher and persistence layer handles cross-process safety.
 */
internal class FeedbackQueue(
    private val initialQueue: List<FeedbackEvent.FeedbackEntry> = emptyList(),
    private val initialDismissed: Set<String> = emptySet(),
) {
    private val queue: ArrayDeque<FeedbackEvent.FeedbackEntry> =
        ArrayDeque(initialQueue)
    private val dismissed: MutableSet<String> =
        HashSet(initialDismissed)

    /** Snapshot of the queue, oldest first (the order they will be flushed). */
    fun snapshot(): List<FeedbackEvent.FeedbackEntry> = queue.toList()

    /** Snapshot of dismissed eventIds. */
    fun dismissedSnapshot(): Set<String> = dismissed.toSet()

    /** Current size — exposed so the ViewModel can decide whether to flush. */
    fun size(): Int = queue.size

    /**
     * Enqueue an entry. Drops the oldest if the queue is full.
     * Returns the dropped entry (or null) so tests / callers can audit.
     */
    fun enqueue(entry: FeedbackEvent.FeedbackEntry): FeedbackEvent.FeedbackEntry? {
        queue.addLast(entry)
        var dropped: FeedbackEvent.FeedbackEntry? = null
        while (queue.size > QUEUE_CAPACITY) {
            dropped = queue.removeFirst()
        }
        return dropped
    }

    /** Drop and return the first entry — used by the flush loop. */
    fun popFirst(): FeedbackEvent.FeedbackEntry? = queue.removeFirstOrNull()

    /**
     * Mark an eventId as dismissed. Idempotent: re-marking the same id
     * leaves the set size unchanged (spec D3 — dismiss MUST never re-nag
     * the same event).
     */
    fun markDismissed(eventId: String) {
        dismissed.add(eventId)
    }

    fun isDismissed(eventId: String): Boolean = eventId in dismissed

    /**
     * Clear the queue (used after a successful flush or when wiping state).
     */
    fun clearQueue() {
        queue.clear()
    }

    /**
     * Build the offline queue + dismissed snapshot as a pair, ready for
     * persistence write-back.
     */
    fun export(): Pair<List<FeedbackEvent.FeedbackEntry>, Set<String>> = snapshot() to dismissedSnapshot()

    companion object {
        /**
         * Hard cap on queued feedback (spec D3 — "cap 25 FIFO" from the
         * feedback design contract #2460).
         */
        const val QUEUE_CAPACITY = 25
    }
}

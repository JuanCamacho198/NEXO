package com.nexo.debug

import android.util.Log
import io.sentry.Sentry
import io.sentry.protocol.Feedback
import io.sentry.protocol.SentryId

/**
 * Thin wrapper around `Sentry.captureFeedback` that enforces the privacy
 * allowlist (spec D2 — context-attachment policy).
 *
 * Policy:
 * - `bookId`, `chapterIndex`, `page` are ALWAYS attached (ids/counters only).
 * - `bookTitle`, `chapterLabel` are attached ONLY on feedback events
 *   (the feedback path is the ONLY writer of those keys).
 * - Note text / chapter text / library content is NEVER attached.
 *
 * Captures are wrapped in `runCatching` so the feedback sheet stays usable
 * when Sentry is uninitialized (DSN empty / before init) — same defensive
 * pattern as `DebugLog`.
 */
internal object FeedbackCapture {
    private const val TAG = "FeedbackCapture"

    /**
     * Build the Sentry [Feedback] payload from a queue entry + book context.
     * `eventId` is the Sentry eventId of the originating crash; the
     * `setAssociatedEventId` link makes the feedback ↔ crash pair visible
     * in the Sentry UI (spec D3).
     */
    fun build(entry: FeedbackEvent.FeedbackEntry): Feedback {
        val feedback = Feedback(entry.text)
        runCatching {
            feedback.setAssociatedEventId(SentryId(entry.eventId))
        }.onFailure {
            // eventId is a hex UUID — if it's malformed we still send the
            // feedback (without the link) rather than swallow the report.
            Log.w(TAG, "feedback: invalid eventId '${entry.eventId}', sending unlinked")
        }
        val bookContext =
            buildMap {
                put("bookId", entry.bookId ?: "")
                if (entry.chapterIndex != null) put("chapterIndex", entry.chapterIndex)
                if (entry.page != null) put("page", entry.page)
                if (entry.title != null) put("title", entry.title.take(100))
                if (entry.chapterLabel != null) put("chapterLabel", entry.chapterLabel.take(80))
            }
        // Feedback uses `unknown` for arbitrary payload (Sentry envelope schema).
        feedback.unknown = mapOf("book" to bookContext)
        return feedback
    }

    /**
     * Submit a [FeedbackEvent.FeedbackEntry] to Sentry. Returns the SentryId
     * on success or null when SDK is uninitialized / capture throws.
     */
    fun submit(entry: FeedbackEvent.FeedbackEntry): SentryId? {
        val feedback = build(entry)
        return runCatching { Sentry.captureFeedback(feedback) }.getOrElse {
            Log.w(TAG, "feedback: captureFeedback failed: ${it.message}")
            null
        }
    }

    /**
     * Helper used by [FeedbackSheet] / [FeedbackViewModel] when the user types
     * a fresh message (not a queued entry).
     */
    fun submitText(
        text: String,
        eventId: String?,
        book: FeedbackEvent.BookMeta,
    ): SentryId? {
        val entry =
            FeedbackEvent.FeedbackEntry(
                eventId = eventId ?: "",
                text = text,
                timestamp = System.currentTimeMillis(),
                bookId = book.bookId,
                chapterIndex = book.chapterIndex,
                page = book.page,
                title = book.title,
                chapterLabel = book.chapterLabel,
            )
        return submit(entry)
    }
}

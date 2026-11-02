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
 * - Diagnostics ARE attached, as `logs`, sourced from [CrashLogStore].
 *
 * ## Why the log snapshot is safe to attach
 *
 * The `logs` key carries the same `CrashLogStore` snapshot the local crash file
 * already holds, so a reviewer sees what the app was doing when it crashed.
 * That is only safe because book content never enters a log line in the first
 * place: `BookContentLogHygieneTest` gates every `Log.*` / `DebugLog.*` call in
 * `presentation/` and `debug/` for interpolated content, and the selection
 * pipeline logs the SHAPE of a value (`len`, `words`, `rect`, `id`) instead of
 * the value. See `ReadiumSelectionBridge` and `SelectionManager` — those sites
 * deliberately carry no text.
 *
 * Two further boundaries apply before egress: newest-first is reversed to
 * chronological, and every line passes through [LogRedaction].
 *
 * Note that `LogRedaction` here is the PRIMARY boundary for these logs, not
 * merely defence in depth: `captureFeedback` builds an envelope of its own, and
 * `SentryOptions.beforeSend` / [SentryPiiScrubber] operate on buffered
 * `SentryEvent`s, so they are not on this path. The per-line scrub in
 * [attachedLogs] is therefore what actually stands between the log content and
 * Sentry. It is applied unconditionally, including when the
 * [BookContentLogHygieneTest] source gate is green.
 *
 * ## The one deliberate exception: reader selection translation
 *
 * Book content never leaves the device through the feedback or logging paths
 * above. The single, explicit exception is the reader's **Translate** action
 * (`AnchoredTranslationPanel` → `MyMemoryTranslationClient`): when the user
 * taps it, the selected word or phrase is sent to the public MyMemory API
 * (`https://api.mymemory.translated.net/get`) to obtain a translation. It is
 * initiated by the user, scoped to the selection (never the chapter), and no
 * API key or account identifier is attached. The translation is cached locally
 * in `translation_cache` so a repeat does not re-send. Every other egress path
 * in this file keeps the "book content never leaves" guarantee.
 *
 * Captures are wrapped in `runCatching` so the feedback sheet stays usable
 * when Sentry is uninitialized (DSN empty / before init) — same defensive
 * pattern as `DebugLog`.
 */
internal object FeedbackCapture {
    private const val TAG = "FeedbackCapture"

    /**
     * Cap on the attached log lines. [CrashLogStore.snapshot] already caps at
     * 200 newest lines; sending all of them plus a book context lands the
     * feedback envelope near ~15 KB, an order of magnitude under Sentry's
     * 200 KB compressed / 1 MB decompressed event ceiling, so the payload is
     * not the binding constraint. The cap is set at 120 to keep the dense tail
     * of the crash — the recent lines that explain it — while dropping the
     * older, noisier prefix. The crash file on disk keeps the full 200.
     */
    private const val MAX_ATTACHED_LOG_LINES = 120

    /**
     * Supplier for the log snapshot. Installed once from
     * `NexoApplication.onCreate` (where the [CrashLogStore] is constructed)
     * rather than injected: `FeedbackCapture` is an `object` reached from
     * `FeedbackViewModel`'s static default capture, so a constructor or
     * parameter route would mean converting it to a class and threading the
     * store through the ViewModel factory — a much wider blast radius for the
     * same result. Unset (null) simply means no logs are attached, which is the
     * correct behaviour for tests and any path that runs before `onCreate`
     * finishes.
     */
    @Volatile
    private var logSource: (() -> List<String>)? = null

    /** Install the snapshot source. Idempotent; last writer wins. */
    fun installLogSource(source: () -> List<String>) {
        logSource = source
    }

    /** Test seam — clears the installed source. */
    internal fun resetForTest() {
        logSource = null
    }

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
        feedback.unknown =
            buildMap {
                put("book", bookContext)
                attachedLogs()?.let { put("logs", it) }
            }
        return feedback
    }

    /**
     * The log lines to attach, oldest-first so the reader follows the thread of
     * causes into the crash, capped at [MAX_ATTACHED_LOG_LINES] (keeping the
     * NEWEST lines: `snapshot()` is newest-first, so `take` before `reversed`).
     *
     * Every line passes through [LogRedaction] as defence in depth — the same
     * boundary applied to logcat and the crash file. Returns null when no
     * source is installed or the snapshot is empty, so a test or a pre-init
     * capture simply attaches nothing rather than an empty array.
     */
    private fun attachedLogs(): List<String>? {
        val lines = runCatching { logSource?.invoke() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        return lines
            .take(MAX_ATTACHED_LOG_LINES)
            .asReversed()
            .map { LogRedaction.redactString(it) }
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

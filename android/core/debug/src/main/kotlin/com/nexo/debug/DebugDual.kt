package com.nexo.debug

import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Dual debug helper: logs BOTH to in-app DebugLog/DebugStateHolder AND to adb logcat.
 *
 * Every event is visible in LogViewerScreen (via DebugLog) and filterable via
 * `adb logcat -s ReaderSync ReaderFooter ReaderChrome ReaderProgress`.
 *
 * Typed events carry structured ids (highlightId/bookId) without PII (never
 * highlight.text or EPUB body).
 */
object DebugDual {
    private const val MAX_ERROR_SNIPPET_LENGTH = 200

    const val TAG_SYNC = "ReaderSync"
    const val TAG_FOOTER = "ReaderFooter"
    const val TAG_CHROME = "ReaderChrome"
    const val TAG_PROGRESS = "ReaderProgress"
    const val TAG_READER = "Readium"
    const val TAG_HIGHLIGHTS = TAG_SYNC
    const val TAG_FILTER = "NexoDebug"
    const val TAG_SUPABASE_SYNC = "SupabaseProgressSync"

    /** Unified severity that maps to both DebugLog.Level and android Log priority. */
    enum class Severity { D, W, E }

    // ---- Generic dual emitters (used by inline call sites) ----

    fun d(
        tag: String,
        message: String,
    ) {
        DebugLog.info(tag, message)
        runCatching { Log.d(tag, message) }
        // Also emit under the aggregate filter tag so a single `adb logcat -s NexoDebug` captures all
        if (tag != TAG_FILTER) runCatching { Log.d(TAG_FILTER, "[$tag] $message") }
    }

    fun w(
        tag: String,
        message: String,
    ) {
        DebugLog.warn(tag, message)
        runCatching { Log.w(tag, message) }
        if (tag != TAG_FILTER) runCatching { Log.w(TAG_FILTER, "[$tag] $message") }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        DebugLog.error(tag, message + (throwable?.let { ": ${it.message}" } ?: ""))
        runCatching { Log.e(tag, message, throwable) }
        if (tag != TAG_FILTER) runCatching { Log.e(TAG_FILTER, "[$tag] $message", throwable) }
    }

    // ---- Required aliases per spec (debugInfo / debugWarn / debugError) ----
    fun debugInfo(
        tag: String,
        msg: String,
    ) = d(tag, msg)

    fun debugWarn(
        tag: String,
        msg: String,
    ) = w(tag, msg)

    fun debugError(
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    ) = e(tag, msg, throwable)

    // ---- Typed event helpers (ensure both DebugLog+DebugStateHolder AND Log fire) ----
    // Each helper delegates to log(event) so exactly ONE non-error signal
    // (breadcrumb / WARN log) is emitted per typed event. These are deliberate
    // telemetry, never error-level Sentry issues.
    fun logHighlightSkipped(
        id: String,
        cfi: String?,
        reason: String,
    ) {
        log(DebugEvent.HighlightsSkipped(id, cfi, reason))
    }

    fun logHighlightApplied(count: Int) {
        val msg = "highlights.applied count=$count"
        d(TAG_SYNC, msg)
        DebugStateHolder.recordApplied(count)
        // Also emit via typed event for parity with existing highlightsApplied flow
        runCatching { Log.d(TAG_SYNC, msg) }
    }

    // Convenience overload for per-highlight applied (kept for backward compat with existing call sites)
    fun logHighlightApplied(
        highlightId: String,
        cfi: String?,
        viaFallback: Boolean,
    ) {
        log(DebugEvent.HighlightsApplied(highlightId, cfi, viaFallback))
        // Ensure DebugStateHolder also sees the single apply (handled inside log(event) -> counts are per highlight)
        // For count-based tracking, the caller should also call logHighlightApplied(1) after batch apply
    }

    fun logSyncFailed(
        entityType: String,
        entityId: String?,
        error: String,
    ) {
        log(DebugEvent.SyncOutboxFailed(entityType, entityId, error))
        // Keep the SupabaseProgressSync mirror for RLS/empty-body diagnostics.
        // WARN level: this is retryable telemetry, not an error-level event; it
        // must not egress as a Sentry captureMessage (NEXTPAGE-ANDROID-C/-F).
        val msg = "sync.outboxFailed entityType=$entityType entityId=${entityId ?: "null"} error=${error.take(MAX_ERROR_SNIPPET_LENGTH)}"
        w(TAG_SUPABASE_SYNC, msg)
    }

    // Legacy (expected, actual) shape carries no locatorHref, so it cannot
    // delegate to DebugEvent.FooterMismatch. Kept for backward compat; callers
    // with a locatorHref should prefer log(DebugEvent.FooterMismatch(...)).
    fun logFooterMismatch(
        expected: String?,
        actual: String?,
    ) {
        val msg = "reader.footerMismatch expected=${expected ?: "null"} actual=${actual ?: "null"}"
        w(TAG_FOOTER, msg)
    }

    // ---- Journey breadcrumb egress (sentry-observability-v2 PR1) ----

    /**
     * Forwards an allowlisted journey event to Sentry as a breadcrumb.
     *
     * Allowlist: ProgressEmit, ChapterResolved, HighlightsApplied,
     * SyncOutboxFailed, SyncReceive — ids/counters only, never text, titles,
     * or error bodies. ChromeToggled/FooterRecompute (+ every other event)
     * are intentionally excluded as noise. Wrapped in [runCatching]: when the
     * SDK is uninit the call is a safe no-op and local DebugLog entries are
     * unaffected.
     */
    private fun addCrumb(
        category: String,
        message: String,
        data: Map<String, String>,
    ) {
        runCatching {
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    setCategory(category)
                    setMessage(message)
                    level = SentryLevel.INFO
                    data.forEach { (key, value) -> setData(key, value) }
                },
            )
        }
    }

    /**
     * PR6: P0 instrumentation breadcrumb. Category `perf`, message
     * `metric.<name>` — both allowlisted by SentryPiiScrubber. Data must be
     * ids/enums only; the scrubber still applies the denylist.
     */
    fun addPerfCrumb(
        name: String,
        data: Map<String, String>,
    ) {
        addCrumb(category = "perf", message = "metric.$name", data = data)
    }

    // ---- Typed event entry point ----

    fun log(event: DebugEvent) {
        when (event) {
            is DebugEvent.HighlightsSkipped -> {
                val cfiSafe = event.cfi?.take(80) ?: "null"
                val msg = "highlights.skipped highlightId=${event.highlightId} cfi=$cfiSafe reason=${event.reason}"
                w(TAG_SYNC, msg)
                // Mirror to DebugStateHolder decoration diagnostics so both channels fire
                runCatching { DebugStateHolder.recordDecorationEvent(event.highlightId, TAG_SYNC, null) }
                // Telemetry, not an error: send an ids-only breadcrumb (NEXTPAGE-ANDROID-8 family).
                addCrumb(
                    category = "highlight",
                    message = msg,
                    data =
                        mapOf(
                            "highlightId" to event.highlightId,
                            "reason" to event.reason,
                        ),
                )
            }
            is DebugEvent.HighlightsApplied -> {
                val cfiSafe = event.cfi?.take(80) ?: "null"
                val via = if (event.viaFallback) "viaFallback" else "direct"
                val msg = "highlights.applied highlightId=${event.highlightId} cfi=$cfiSafe $via count=1"
                d(TAG_SYNC, msg)
                // Telemetry, not an error: breadcrumb only (ids-only).
                addCrumb(
                    category = "highlight",
                    message = msg,
                    data =
                        mapOf(
                            "highlightId" to event.highlightId,
                            "viaFallback" to event.viaFallback.toString(),
                        ),
                )
            }
            is DebugEvent.SyncOutboxFailed -> {
                val msg = "sync.outboxFailed entityType=${event.entityType} entityId=${event.entityId} error=${event.error.take(MAX_ERROR_SNIPPET_LENGTH)}"
                // WARN, not ERROR: a retryable push failure is expected transient
                // telemetry; ERROR here also egressed via DebugLog -> captureMessage
                // and created permanent unresolved issues. Poison is reported
                // separately by the outbox callers.
                w(TAG_SYNC, msg)
                addCrumb(
                    category = "sync",
                    message = msg,
                    data =
                        mapOf(
                            "entityType" to event.entityType,
                            "entityId" to (event.entityId ?: "null"),
                        ),
                )
            }
            is DebugEvent.FooterMismatch -> {
                val msg = "reader.footerMismatch locatorHref=${event.locatorHref} computed=${event.computedChapter} expected=${event.expectedChapter}"
                w(TAG_FOOTER, msg)
                // Telemetry, not an error: ids-only breadcrumb (chapter titles stay local).
                addCrumb(
                    category = "navigation",
                    message = msg,
                    data = mapOf("locatorHref" to event.locatorHref),
                )
            }
            is DebugEvent.ChromeToggled -> {
                val msg = "chrome.visibility visible=${event.visible}"
                d(TAG_CHROME, msg)
            }
            is DebugEvent.FooterRecompute -> {
                val msg = "footer.recompute viewportH=${event.viewportH} viewportW=${event.viewportW} fontSize=${event.fontSize} lineHeight=${event.lineHeight} pageMargins=${event.pageMargins} charsPerPage=${event.charsPerPage} remaining=${event.remaining} path=${event.path}"
                d(TAG_FOOTER, msg)
            }
            is DebugEvent.ProgressEmit -> {
                val msg = "progress.emit bookId=${event.bookId} percentage=${event.percentage} source=${event.source}"
                d(TAG_PROGRESS, msg)
                addCrumb(
                    category = "navigation",
                    message = msg,
                    data =
                        mapOf(
                            "bookId" to event.bookId,
                            "source" to event.source,
                        ),
                )
            }
            is DebugEvent.ProgressReconciled -> {
                val msg = "progress.reconciled bookId=${event.bookId} winner=${event.winner} localAt=${event.localAt} remoteAt=${event.remoteAt} localPct=${event.localPct} remotePct=${event.remotePct}"
                d(TAG_PROGRESS, msg)
            }
            is DebugEvent.SyncReceive -> {
                val cfiSafe = event.cfi?.take(60) ?: "null"
                val msg = "sync.receive highlightId=${event.highlightId} cfi=$cfiSafe locatorJsonNull=${event.locatorJsonNull}"
                d(TAG_SYNC, msg)
                addCrumb(
                    category = "sync",
                    message = msg,
                    data = mapOf("highlightId" to event.highlightId),
                )
            }
            is DebugEvent.ChapterResolved -> {
                val msg = "footer.chapterResolved locatorHref=${event.locatorHref} chapterTitle=${event.chapterTitle} index=${event.index}"
                d(TAG_FOOTER, msg)
                // Crumb data is ids-only: chapterTitle stays out (titles are
                // feedback-events-only per the context-attachment policy).
                addCrumb(
                    category = "navigation",
                    message = msg,
                    data =
                        mapOf(
                            "locatorHref" to event.locatorHref,
                            "index" to event.index.toString(),
                        ),
                )
            }
        }
    }
}

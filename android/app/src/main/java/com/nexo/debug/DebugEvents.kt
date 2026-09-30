package com.nexo.debug

/**
 * Typed debug events for dual-channel observability.
 *
 * No PII beyond bookId/highlightId; never log highlight.text or EPUB body.
 */
sealed class DebugEvent {
    data class HighlightsSkipped(
        val highlightId: String,
        val cfi: String?,
        val reason: String,
    ) : DebugEvent()

    data class HighlightsApplied(
        val highlightId: String,
        val cfi: String?,
        val viaFallback: Boolean,
    ) : DebugEvent()

    data class SyncOutboxFailed(
        val entityType: String,
        val entityId: String?,
        val error: String,
    ) : DebugEvent()

    data class FooterMismatch(
        val locatorHref: String,
        val computedChapter: String?,
        val expectedChapter: String?,
    ) : DebugEvent()

    data class ChromeToggled(
        val visible: Boolean,
    ) : DebugEvent()

    data class FooterRecompute(
        val viewportH: Int,
        val viewportW: Int,
        val fontSize: Float,
        val lineHeight: Float,
        val pageMargins: Float,
        val charsPerPage: Int,
        val remaining: Int,
        val path: String, // "positions" or "fallback"
    ) : DebugEvent()

    data class ProgressEmit(
        val bookId: String,
        val percentage: Float,
        val source: String,
    ) : DebugEvent()

    data class ProgressReconciled(
        val bookId: String,
        val winner: String, // "local" or "remote" or "canonical"
        val localAt: Long?,
        val remoteAt: Long?,
        val localPct: Float?,
        val remotePct: Float?,
    ) : DebugEvent()

    data class SyncReceive(
        val highlightId: String,
        val cfi: String?,
        val locatorJsonNull: Boolean,
    ) : DebugEvent()

    data class ChapterResolved(
        val locatorHref: String,
        val chapterTitle: String?,
        val index: Int,
    ) : DebugEvent()
}

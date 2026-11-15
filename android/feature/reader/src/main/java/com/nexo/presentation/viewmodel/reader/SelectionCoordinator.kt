package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import android.os.SystemClock
import com.nexo.domain.model.Highlight
import org.readium.r2.shared.publication.Locator

/**
 * Sealed state machine for the selection + context menu lifecycle.
 *
 * Replaces the loose coupling between [activeHighlightId],
 * [highlightTapDebounceUntil], [menuJustClosedAt], and
 * [clearSelectionEvent] with explicit state transitions.
 *
 * This is NOT a state holder (no StateFlow). It's a pure state machine
 * used internally by [ReaderInteractionStateHolder].
 */
sealed interface SelectionCoordinator {
    /** No selection, no debounce, no menu. Default state. */
    data object Idle : SelectionCoordinator

    /** User selected new text (not a highlight tap). */
    data class NewSelection(
        val text: String,
        val rect: Rect,
        val locator: Locator?,
        val createdAt: Long = SystemClock.elapsedRealtime(),
    ) : SelectionCoordinator

    /** User tapped an existing highlight — context menu is shown. */
    data class ExistingHighlight(
        val highlight: Highlight,
        val rect: Rect,
        val debounceUntil: Long,
        val createdAt: Long = SystemClock.elapsedRealtime(),
    ) : SelectionCoordinator {
        val activeHighlightId: String get() = highlight.id
    }

    /** Menu was just dismissed — briefly ignore selection events. */
    data class MenuClosed(
        val closedAt: Long = SystemClock.elapsedRealtime(),
    ) : SelectionCoordinator
}

/** Debounce duration after highlight tap (ms). */
internal const val HIGHLIGHT_TAP_DEBOUNCE_MS = 2000L

/** Ignore selection events for this long after menu closes (ms). */
internal const val MENU_CLOSE_IGNORE_MS = 1500L

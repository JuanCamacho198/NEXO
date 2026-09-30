package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import android.graphics.RectF
import com.nexo.domain.model.Highlight
import org.readium.r2.shared.publication.Locator

/**
 * Sealed representation of the reader's floating-menu state.
 *
 * Replaces the loose boolean flags (`showColorPicker`, `showContextMenu`, etc.)
 * with an explicit, mutually-exclusive state machine:
 * - [None]: no selection is active.
 * - [New]: the user has selected new text and should see the creation menu.
 * - [Existing]: the user tapped an existing highlight and should see the edit menu.
 *
 * NOTE: [New] and [Existing] are **not** `data class`es because they hold
 * `android.graphics.Rect` fields whose `equals()`/`toString()` throw in
 * unit tests unless mocked. Custom `equals`/`hashCode`/`toString` skip
 * `rect` — the rect is transient UI-positioning data that should not
 * influence state-flo w deduplication.
 */
sealed interface ReaderSelectionState {
    data object None : ReaderSelectionState

    class New(
        val rect: Rect,
        val text: String,
        val locator: Locator?,
    ) : ReaderSelectionState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is New) return false
            return text == other.text && locator == other.locator
        }

        override fun hashCode(): Int = 31 * text.hashCode() + (locator?.hashCode() ?: 0)

        override fun toString(): String = "New(rect=$rect, text='$text', locator=$locator)"
    }

    class Existing(
        val highlight: Highlight,
        val rect: Rect,
    ) : ReaderSelectionState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Existing) return false
            return highlight == other.highlight
        }

        override fun hashCode(): Int = highlight.hashCode()

        override fun toString(): String = "Existing(highlight=$highlight, rect=$rect)"
    }
}

/** Converts a Readium viewport-space [RectF] to an Android [Rect] for [ReaderSelectionState]. */
internal fun RectF.toRect(): Rect =
    Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt(),
    )

package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight

/**
 * Annotation slice alias (SDD reader-facade-split, slice 5): VM re-export
 * type. The annotation owner is [ReaderInteractionStateHolder] (still the
 * deprecated PR #3 facade at PR-D time; PR-D does not remove it — that
 * lands with PR #3 itself, after which the slice's owner becomes the
 * [com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore]
 * shared across the 5 interaction managers). The typealias keeps the public
 * slice name stable across that transition.
 *
 * Fields map to the design's `AnnotationUiState(selection, highlights,
 * bookmarks, annotations, noteModal, tagInput, panel, toc)`: `selection*`
 * covers selectionState/selectedText/selectionRect; `highlights` carries
 * annotations (note/tag/colour per entry); `bookmarks` is its own stream;
 * `showNoteModal` / `activeNoteText` / `showTagInput` / `activeTagText` /
 * `tagSuggestions` cover note modal + tag input; `showHighlightsSheet` is
 * the panel; `showDefinitionInput` / `activeDefinitionText` cover the
 * definition input (annotation-adjacent, lives in the same surface for
 * back-compat). The `toc` field is owned by the session slice, not here.
 */
typealias AnnotationUiState = ReaderInteractionState

/**
 * UI state managed by [ReaderInteractionStateHolder].
 *
 * NOTE: [activeHighlightId], [highlightTapDebounceUntil], and
 * [menuJustClosedAt] are NOT here — they're internal to
 * [SelectionCoordinator] state machine.
 *
 * NOTE: Not a `data class` because [selectionRect] is an Android [Rect]
 * whose `equals()` is not available in JVM unit tests. Custom
 * [equals]/[hashCode]/[toString] skip [selectionRect] — the rect is
 * transient UI-positioning data that should not influence state-flow
 * deduplication.
 */
class ReaderInteractionState(
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val selectionState: ReaderSelectionState = ReaderSelectionState.None,
    val selectedText: String? = null,
    val selectionRect: Rect? = null,
    val showColorPickerPopover: Boolean = false,
    val showNoteModal: Boolean = false,
    val activeNoteText: String = "",
    val showTagInput: Boolean = false,
    val activeTagText: String = "",
    val tagSuggestions: List<String> = emptyList(),
    val showDefinitionInput: Boolean = false,
    val activeDefinitionText: String = "",
    val showHighlightsSheet: Boolean = false,
    val debugForceMenu: Boolean = false,
) {
    fun copy(
        highlights: List<Highlight> = this.highlights,
        bookmarks: List<Bookmark> = this.bookmarks,
        selectionState: ReaderSelectionState = this.selectionState,
        selectedText: String? = this.selectedText,
        selectionRect: Rect? = this.selectionRect,
        showColorPickerPopover: Boolean = this.showColorPickerPopover,
        showNoteModal: Boolean = this.showNoteModal,
        activeNoteText: String = this.activeNoteText,
        showTagInput: Boolean = this.showTagInput,
        activeTagText: String = this.activeTagText,
        tagSuggestions: List<String> = this.tagSuggestions,
        showDefinitionInput: Boolean = this.showDefinitionInput,
        activeDefinitionText: String = this.activeDefinitionText,
        showHighlightsSheet: Boolean = this.showHighlightsSheet,
        debugForceMenu: Boolean = this.debugForceMenu,
    ): ReaderInteractionState =
        ReaderInteractionState(
            highlights = highlights,
            bookmarks = bookmarks,
            selectionState = selectionState,
            selectedText = selectedText,
            selectionRect = selectionRect,
            showColorPickerPopover = showColorPickerPopover,
            showNoteModal = showNoteModal,
            activeNoteText = activeNoteText,
            showTagInput = showTagInput,
            activeTagText = activeTagText,
            tagSuggestions = tagSuggestions,
            showDefinitionInput = showDefinitionInput,
            activeDefinitionText = activeDefinitionText,
            showHighlightsSheet = showHighlightsSheet,
            debugForceMenu = debugForceMenu,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReaderInteractionState) return false
        return highlights == other.highlights &&
            bookmarks == other.bookmarks &&
            selectionState == other.selectionState &&
            selectedText == other.selectedText &&
            showColorPickerPopover == other.showColorPickerPopover &&
            showNoteModal == other.showNoteModal &&
            activeNoteText == other.activeNoteText &&
            showTagInput == other.showTagInput &&
            activeTagText == other.activeTagText &&
            tagSuggestions == other.tagSuggestions &&
            showDefinitionInput == other.showDefinitionInput &&
            activeDefinitionText == other.activeDefinitionText &&
            showHighlightsSheet == other.showHighlightsSheet &&
            debugForceMenu == other.debugForceMenu
        // selectionRect intentionally omitted — not mockable in JVM unit tests
    }

    override fun hashCode(): Int {
        var result = highlights.hashCode()
        result = 31 * result + bookmarks.hashCode()
        result = 31 * result + selectionState.hashCode()
        result = 31 * result + (selectedText?.hashCode() ?: 0)
        result = 31 * result + showColorPickerPopover.hashCode()
        result = 31 * result + showNoteModal.hashCode()
        result = 31 * result + activeNoteText.hashCode()
        result = 31 * result + showTagInput.hashCode()
        result = 31 * result + activeTagText.hashCode()
        result = 31 * result + tagSuggestions.hashCode()
        result = 31 * result + showDefinitionInput.hashCode()
        result = 31 * result + activeDefinitionText.hashCode()
        result = 31 * result + showHighlightsSheet.hashCode()
        result = 31 * result + debugForceMenu.hashCode()
        return result
    }

    override fun toString(): String =
        "ReaderInteractionState(" +
            "highlights.size=${highlights.size}, " +
            "bookmarks.size=${bookmarks.size}, " +
            "selectionState=$selectionState, " +
            "selectedText='$selectedText', " +
            "selectionRect=$selectionRect, " +
            "showColorPickerPopover=$showColorPickerPopover, " +
            "showNoteModal=$showNoteModal, " +
            "activeNoteText='$activeNoteText', " +
            "showTagInput=$showTagInput, " +
            "activeTagText='$activeTagText', " +
            "tagSuggestions=$tagSuggestions, " +
            "showDefinitionInput=$showDefinitionInput, " +
            "activeDefinitionText='$activeDefinitionText', " +
            "showHighlightsSheet=$showHighlightsSheet, " +
            "debugForceMenu=$debugForceMenu" +
            ")"
}

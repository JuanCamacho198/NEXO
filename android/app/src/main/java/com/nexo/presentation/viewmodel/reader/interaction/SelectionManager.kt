package com.nexo.presentation.viewmodel.reader.interaction

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.viewmodel.reader.HIGHLIGHT_TAP_DEBOUNCE_MS
import com.nexo.presentation.viewmodel.reader.MENU_CLOSE_IGNORE_MS
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.presentation.viewmodel.reader.SelectionCoordinator
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

/**
 * Owns SelectionCoordinator state machine and all selection/menu transitions.
 * Single source of truth for HIGHLIGHT_TAP 2000ms and MENU_CLOSE 1500ms debounce.
 * Delegates atomic dismiss to [InteractionStateStore.clearSelection].
 */
internal class SelectionManager(
    private val store: InteractionStateStore,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
) : Clearable {
    companion object {
        private const val TAG = "SelectionManager"
        private const val DEBUG_LOG_TEXT_LIMIT = 50
        private val DEBUG_FORCE_MENU_RECT = Rect(200, 200, 600, 250)
    }

    internal var coordinator: SelectionCoordinator = SelectionCoordinator.Idle

    fun activeHighlightId(): String? =
        when (val c = coordinator) {
            is SelectionCoordinator.ExistingHighlight -> c.activeHighlightId
            else -> null
        }

    // ── Selection pipeline ──────────────────────────────────────

    fun onReadiumSelection(
        locator: Locator,
        rect: RectF,
        text: String,
        existingHighlights: List<Highlight>,
    ) {
        Log.d(
            "SelectionDebug",
            "VM.onReadiumSelection: text='', rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}], locator.href=${locator.href}",
        )
        val now = SystemClock.elapsedRealtime()
        when (val current = coordinator) {
            is SelectionCoordinator.MenuClosed -> {
                if (now - current.closedAt < MENU_CLOSE_IGNORE_MS) {
                    Log.d("SelectionDebug", "Ignoring selection after menu close (${now - current.closedAt}ms ago)")
                    return
                }
                coordinator = SelectionCoordinator.Idle
            }
            is SelectionCoordinator.ExistingHighlight -> {
                val activeHighlight = current.highlight
                val textMatchesActive =
                    activeHighlight.textContent.isNotBlank() &&
                        (
                            text == activeHighlight.textContent ||
                                text.contains(activeHighlight.textContent) ||
                                activeHighlight.textContent.contains(text)
                        )
                if (now < current.debounceUntil) {
                    if (textMatchesActive) {
                        Log.d("SelectionDebug", "Ignoring selection during highlight-tap debounce (matches active highlight)")
                        DebugLog.warn(TAG, "onReadiumSelection IGNORED (debounce active, matches active highlight)")
                        return
                    }
                    DebugLog.info(TAG, "Debounce active but selection text doesn't match active highlight — overriding")
                } else {
                    DebugLog.info(TAG, "Debounce expired — falling through to new-selection menu")
                }
            }
            is SelectionCoordinator.Idle,
            is SelectionCoordinator.NewSelection,
            -> {}
        }

        val selectionRect =
            try {
                Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
            } catch (e: Throwable) {
                Log.e("SelectionDebug", "Rect creation THREW: ${e::class.simpleName}: ${e.message}", e)
                Rect(0, 0, 100, DEBUG_LOG_TEXT_LIMIT)
            }

        try {
            val normalizedText = text.trim().replace(Regex("\\s+"), " ")
            coordinator = SelectionCoordinator.NewSelection(normalizedText, selectionRect, locator)
            store.update {
                it.copy(
                    selectionState = ReaderSelectionState.New(rect = selectionRect, text = normalizedText, locator = locator),
                    selectedText = normalizedText,
                    selectionRect = selectionRect,
                )
            }
            DebugLog.info(TAG, "onReadiumSelection: selectionState=New")
            Log.d("SelectionDebug", "VM.onReadiumSelection state update OK")
        } catch (e: Throwable) {
            Log.e("SelectionDebug", "VM.onReadiumSelection state update THREW: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    fun onSelectionCleared() {
        val activeId =
            when (val c = coordinator) {
                is SelectionCoordinator.ExistingHighlight -> c.activeHighlightId
                else -> null
            }
        val debounceUntil =
            when (val c = coordinator) {
                is SelectionCoordinator.ExistingHighlight -> c.debounceUntil
                else -> 0L
            }
        onSelectionCleared(activeId, debounceUntil)
    }

    fun onSelectionCleared(
        currentActiveHighlightId: String?,
        currentHighlightTapDebounceUntil: Long,
    ) {
        Log.d("SelectionDebug", "VM.onSelectionCleared — resetting selection state")
        try {
            val now = SystemClock.elapsedRealtime()
            if (now < currentHighlightTapDebounceUntil && currentActiveHighlightId != null) {
                Log.d("SelectionDebug", "Ignoring selection-clear during highlight-tap debounce")
                DebugLog.warn(TAG, "onSelectionCleared IGNORED (debounce active until=$currentHighlightTapDebounceUntil, now=$now)")
                return
            }
            DebugLog.info(TAG, "onSelectionCleared (debounce not active)")
            coordinator = SelectionCoordinator.Idle
            store.update {
                it.copy(
                    selectionState = ReaderSelectionState.None,
                    selectedText = null,
                    selectionRect = null,
                    showColorPickerPopover = false,
                    showNoteModal = false,
                    showTagInput = false,
                    showDefinitionInput = false,
                    activeNoteText = "",
                    activeTagText = "",
                    activeDefinitionText = "",
                    tagSuggestions = emptyList(),
                    debugForceMenu = false,
                )
            }
        } catch (e: Throwable) {
            Log.e("SelectionDebug", "VM.onSelectionCleared THREW: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    fun onTextSelectionEvent(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        onTextSelection(text, Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt()))
    }

    fun onTextSelection(
        text: String,
        rect: Rect,
    ) {
        Log.d("ReaderVM", "onTextSelection: \"${text.take(DEBUG_LOG_TEXT_LIMIT)}\" rect=$rect")
        coordinator = SelectionCoordinator.NewSelection(text, rect, null)
        store.update {
            it.copy(selectionState = ReaderSelectionState.New(rect, text, null), selectedText = text, selectionRect = rect)
        }
    }

    fun onHighlightTapped(
        highlight: Highlight,
        rect: RectF,
    ) {
        DebugLog.info(TAG, "onHighlightTapped id=${highlight.id} t=${SystemClock.elapsedRealtime()}")
        val selectionRect = Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        coordinator =
            SelectionCoordinator.ExistingHighlight(
                highlight = highlight,
                rect = selectionRect,
                debounceUntil = SystemClock.elapsedRealtime() + HIGHLIGHT_TAP_DEBOUNCE_MS,
            )
        DebugLog.info(
            TAG,
            "Highlight tapped: id=${highlight.id}, rect=[${selectionRect.left},${selectionRect.top},${selectionRect.right},${selectionRect.bottom}]",
        )
        store.update {
            it.copy(
                selectionState = ReaderSelectionState.Existing(highlight, selectionRect),
                selectedText = highlight.textContent,
                selectionRect = selectionRect,
            )
        }
        DebugLog.info(TAG, "onHighlightTapped: debounce until set, selectionState=Existing")
    }

    // ── Dismiss ─────────────────────────────────────────────────

    fun dismissMenuAndClearSelection() {
        store.clearSelection()
    }

    fun onDismissContextMenu() {
        DebugLog.info(TAG, "Menu dismissed")
        dismissMenuAndClearSelection()
    }

    // ── Lifecycle bridge ────────────────────────────────────────

    fun resetCoordinator() {
        coordinator = SelectionCoordinator.Idle
        store.update {
            it.copy(
                selectionState = ReaderSelectionState.None,
                selectedText = null,
                selectionRect = null,
                showColorPickerPopover = false,
                showNoteModal = false,
                showTagInput = false,
                showDefinitionInput = false,
                activeNoteText = "",
                activeTagText = "",
                activeDefinitionText = "",
                tagSuggestions = emptyList(),
                debugForceMenu = false,
            )
        }
    }

    fun onSelectionClearedFromLifecycle() {
        val activeId =
            when (val c = coordinator) {
                is SelectionCoordinator.ExistingHighlight -> c.activeHighlightId
                else -> null
            }
        val debounceUntil =
            when (val c = coordinator) {
                is SelectionCoordinator.ExistingHighlight -> c.debounceUntil
                else -> 0L
            }
        onSelectionCleared(activeId, debounceUntil)
    }

    // ── Debug ───────────────────────────────────────────────────

    fun onDebugForceMenu() {
        val current = store.value.debugForceMenu
        if (current) {
            onSelectionCleared(currentActiveHighlightId = null, currentHighlightTapDebounceUntil = 0L)
            return
        }
        try {
            val rect = DEBUG_FORCE_MENU_RECT
            val highlight =
                Highlight(
                    id = "debug-highlight",
                    bookId = "debug-book",
                    cfiRange = "epubcfi(/6/1)",
                    textContent = "Texto de prueba debug",
                    note = null,
                    color = HighlightColor.YELLOW.hex,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    deletedAtEpochMillis = null,
                )
            coordinator =
                SelectionCoordinator.ExistingHighlight(
                    highlight = highlight,
                    rect = rect,
                    debounceUntil =
                        SystemClock.elapsedRealtime() + HIGHLIGHT_TAP_DEBOUNCE_MS,
                )
            store.update {
                it.copy(
                    selectedText = "Texto de prueba debug",
                    selectionRect = rect,
                    selectionState = ReaderSelectionState.Existing(highlight, rect),
                    debugForceMenu = true,
                )
            }
        } catch (e: Throwable) {
            DebugLog.warn(TAG, "onDebugForceMenu failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    fun onDebugForceColorPicker() {
        val current = store.value.debugForceMenu
        if (current) {
            onSelectionCleared(currentActiveHighlightId = null, currentHighlightTapDebounceUntil = 0L)
            return
        }
        try {
            val rect = DEBUG_FORCE_MENU_RECT
            coordinator = SelectionCoordinator.NewSelection("Texto de prueba debug", rect, null)
            store.update {
                it.copy(
                    selectedText = "Texto de prueba debug",
                    selectionRect = rect,
                    selectionState = ReaderSelectionState.New(rect = rect, text = "Texto de prueba debug", locator = null),
                    debugForceMenu = true,
                )
            }
        } catch (e: Throwable) {
            DebugLog.warn(TAG, "onDebugForceColorPicker failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    // ── Panel toggle delegated here for atomic isolation ────────

    fun onToggleHighlightsPanel() {
        store.update { it.copy(showHighlightsSheet = !it.showHighlightsSheet) }
    }

    override fun onCleared() {}
}

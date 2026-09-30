package com.nexo.presentation.viewmodel.reader.interaction

import com.nexo.presentation.viewmodel.reader.ReaderInteractionState
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.presentation.viewmodel.reader.SelectionCoordinator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single-flow accessor for ReaderInteractionState.
 * Owns no coordinator state itself; delegates coordinator mutation via [setCoordinator].
 * Ensures [clearSelection] atomicity: 8-field reset + MenuClosed + tryEmit.
 */
internal class InteractionStateStore(
    private val state: MutableStateFlow<ReaderInteractionState>,
    private val clearEvent: MutableSharedFlow<Unit>,
) {
    lateinit var setCoordinator: (SelectionCoordinator) -> Unit

    fun update(transform: (ReaderInteractionState) -> ReaderInteractionState) {
        state.update(transform)
    }

    val value: ReaderInteractionState get() = state.value

    val flow: MutableStateFlow<ReaderInteractionState> get() = state

    /**
     * Atomically resets transient selection/menu fields, sets coordinator to MenuClosed,
     * and emits clearSelectionEvent. Single writer for the 8-field reset path.
     */
    fun clearSelection() {
        setCoordinator(SelectionCoordinator.MenuClosed())
        state.update {
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
            )
        }
        clearEvent.tryEmit(Unit)
    }
}

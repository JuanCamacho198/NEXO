package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState

/** Thin orchestrator — delegates anchoring to AnchoredOverlayBox + computeAnchor. */
@Composable
fun SelectionOverlay(
    selectionState: ReaderSelectionState,
    showColorPickerPopover: Boolean = false,
    showTagInput: Boolean = false,
    tagSuggestions: List<String> = emptyList(),
    activeTagText: String = "",
    showDefinitionInput: Boolean = false,
    activeDefinitionText: String = "",
    selectionRect: Rect?,
    selectedText: String?,
    highlights: List<Highlight>,
    activeHighlightColor: String?,
    customHighlightColors: List<String>? = null,
    onColorSelected: (String) -> Unit,
    onCopy: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: () -> Unit,
    onAnnotate: () -> Unit,
    onShare: () -> Unit,
    onDictionary: () -> Unit,
    onShowColorPickerPopover: () -> Unit = {},
    onDismissColorPickerPopover: () -> Unit = {},
    onTagTextChanged: (String) -> Unit = {},
    onSaveTag: () -> Unit = {},
    onDismissTagInput: () -> Unit = {},
    onDefinitionTextChanged: (String) -> Unit = {},
    onSaveDefinition: () -> Unit = {},
    onDismissDefinitionInput: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val anyMenuVisible = selectionState != ReaderSelectionState.None || showColorPickerPopover || showTagInput || showDefinitionInput
    if (selectionRect == null) return
    val viewportWidth = LocalView.current.width
    val viewportHeight = LocalView.current.height
    if (anyMenuVisible) {
        Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismissContextMenu() })
    }
    if (selectionState is ReaderSelectionState.New && !showTagInput && !showDefinitionInput && !showColorPickerPopover) {
        AnchoredOverlayBox(selectionRect = selectionRect, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            val palette = customHighlightColors ?: HighlightColor.defaultHexList()
            TextSelectionMenu(paletteColors = palette, selectedColor = activeHighlightColor ?: HighlightColor.YELLOW.hex, onColorSelected = onColorSelected, onCopy = onCopy, onDictionary = onDictionary, onShare = onShare)
        }
    }
    if (selectionState is ReaderSelectionState.Existing && !showTagInput && !showDefinitionInput && !showColorPickerPopover) {
        AnchoredOverlayBox(selectionRect = selectionRect, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            FloatingContextMenu(selectedColor = activeHighlightColor ?: HighlightColor.YELLOW.hex, onColorSelected = onShowColorPickerPopover, onCopy = onCopy, onAddTag = onAddTag, onAnnotate = onAnnotate, onShare = onShare, onDelete = onDelete)
        }
    }
    if (showTagInput) {
        AnchoredOverlayBox(selectionRect = selectionRect, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredTagInput(tag = activeTagText, suggestions = tagSuggestions, onTagChange = onTagTextChanged, onSuggestionClick = { tag ->
                onTagTextChanged(tag)
                onSaveTag()
            }, onSave = onSaveTag, onDismiss = onDismissTagInput)
        }
    }
    if (showDefinitionInput) {
        AnchoredOverlayBox(selectionRect = selectionRect, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredDefinitionInput(word = selectedText ?: "", definition = activeDefinitionText, onDefinitionChange = onDefinitionTextChanged, onSave = onSaveDefinition, onDismiss = onDismissDefinitionInput)
        }
    }
    if (showColorPickerPopover) {
        val density = LocalDensity.current
        val anchorCenterX = selectionRect.left + (selectionRect.right - selectionRect.left) / 2
        val anchorBelowY = selectionRect.bottom + with(density) { 12.dp.toPx() }.toInt()
        HighlightColorPickerPopover(
            customColors = customHighlightColors,
            onColorSelected = { c ->
                onColorSelected(c)
                onDismissColorPickerPopover()
            },
            onDismiss = onDismissColorPickerPopover,
            anchorX = anchorCenterX,
            anchorY = anchorBelowY,
            modifier =
                Modifier.offset {
                    val x = (anchorCenterX - 110.dp.toPx().toInt()).coerceAtLeast(0)
                    IntOffset(x, anchorBelowY)
                },
        )
    }
}

private enum class SelectionSurface { TEXT_MENU, CONTEXT_MENU, TAG_INPUT, DEFINITION_INPUT, COLOR_PICKER }

@Composable
private fun SelectionOverlayHost(surface: SelectionSurface) {
    val rect = remember { Rect(50, 100, 250, 150) }
    val hl = remember { Highlight(id = "h1", bookId = "b1", cfiRange = "/4/2", textContent = "Selected sample", note = null, color = HighlightColor.YELLOW.hex, updatedAtEpochMillis = 0L, deletedAtEpochMillis = null) }
    Box(modifier = Modifier.size(340.dp, 380.dp).background(MaterialTheme.colorScheme.background)) {
        SelectionOverlay(
            selectionState =
                when (surface) {
                    SelectionSurface.TEXT_MENU -> ReaderSelectionState.New(rect, "Selected", null)
                    SelectionSurface.CONTEXT_MENU -> ReaderSelectionState.Existing(hl, rect)
                    else -> ReaderSelectionState.None
                },
            showColorPickerPopover = surface == SelectionSurface.COLOR_PICKER,
            showTagInput = surface == SelectionSurface.TAG_INPUT,
            showDefinitionInput = surface == SelectionSurface.DEFINITION_INPUT,
            selectionRect = rect,
            selectedText = "Selected",
            highlights = listOf(hl),
            activeHighlightColor = HighlightColor.YELLOW.hex,
            customHighlightColors = HighlightColor.defaultHexList(),
            onColorSelected = {},
            onCopy = {},
            onDismissContextMenu = {},
            onDelete = {},
            onAddTag = {},
            onAnnotate = {},
            onShare = {},
            onDictionary = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectionOverlayPreview() {
    NexoTheme(darkTheme = true) { SelectionOverlayHost(SelectionSurface.TEXT_MENU) }
}

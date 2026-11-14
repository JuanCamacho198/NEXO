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
import com.nexo.presentation.screen.readium.ReadiumOverlaySpace
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.DefinitionPanelState
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.presentation.viewmodel.reader.TranslationPanelState

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
    showTranslationPanel: Boolean = false,
    translationPanelState: TranslationPanelState = TranslationPanelState.Idle,
    showDefinitionPanel: Boolean = false,
    definitionPanelState: DefinitionPanelState = DefinitionPanelState.Idle,
    definitionLanguage: String = "es",
    definitionWord: String? = null,
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
    onDefine: () -> Unit = {},
    onTranslate: () -> Unit = {},
    onShowColorPickerPopover: () -> Unit = {},
    onDismissColorPickerPopover: () -> Unit = {},
    onTagTextChanged: (String) -> Unit = {},
    onSaveTag: () -> Unit = {},
    onDismissTagInput: () -> Unit = {},
    onDefinitionTextChanged: (String) -> Unit = {},
    onSaveDefinition: () -> Unit = {},
    onDismissDefinitionInput: () -> Unit = {},
    onDismissTranslation: () -> Unit = {},
    onTranslationSourceChosen: (String?) -> Unit = {},
    onTranslationTargetChosen: (String?) -> Unit = {},
    onDismissDefinition: () -> Unit = {},
    onDefinitionLanguageChosen: (String?) -> Unit = {},
    onAddDefinitionToDictionary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val anyMenuVisible = selectionState != ReaderSelectionState.None || showColorPickerPopover || showTagInput || showDefinitionInput || showTranslationPanel || showDefinitionPanel
    if (selectionRect == null) return
    val viewportWidth = LocalView.current.width
    val viewportHeight = LocalView.current.height
    // The selection rect is in the SPACE OF WHAT MEASURED IT. For the reflowable
    // EPUB path that is the navigator's WebView (Readium reports viewport-
    // relative CSS px × devicePixelRatio); for the PDF path there is no WebView
    // and the rect is already in this content box. Locating the WebView here,
    // once, is what lets `AnchoredOverlayBox` convert every surface at exactly
    // one point. `null` means "already in the overlay's space".
    val selectionSource = rememberSelectionSource()
    if (anyMenuVisible) {
        Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismissContextMenu() })
    }
    if (selectionState is ReaderSelectionState.New && !showTagInput && !showDefinitionInput && !showColorPickerPopover && !showTranslationPanel && !showDefinitionPanel) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            TextSelectionMenu(selectedColor = activeHighlightColor, onColorSelected = onShowColorPickerPopover, onCopy = onCopy, onDictionary = onDictionary, onDefine = onDefine, onTranslate = onTranslate, onShare = onShare)
        }
    }
    if (selectionState is ReaderSelectionState.Existing && !showTagInput && !showDefinitionInput && !showColorPickerPopover && !showTranslationPanel && !showDefinitionPanel) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            FloatingContextMenu(selectedColor = activeHighlightColor ?: HighlightColor.YELLOW.hex, onColorSelected = onShowColorPickerPopover, onCopy = onCopy, onDefine = onDefine, onTranslate = onTranslate, onAddTag = onAddTag, onAnnotate = onAnnotate, onShare = onShare, onDelete = onDelete)
        }
    }
    if (showTagInput) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredTagInput(tag = activeTagText, suggestions = tagSuggestions, onTagChange = onTagTextChanged, onSuggestionClick = { tag ->
                onTagTextChanged(tag)
                onSaveTag()
            }, onSave = onSaveTag, onDismiss = onDismissTagInput)
        }
    }
    if (showDefinitionInput) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredDefinitionInput(word = selectedText ?: "", definition = activeDefinitionText, onDefinitionChange = onDefinitionTextChanged, onSave = onSaveDefinition, onDismiss = onDismissDefinitionInput)
        }
    }
    if (showTranslationPanel) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredTranslationPanel(
                sourceWord = selectedText ?: "",
                state = translationPanelState,
                onDismiss = onDismissTranslation,
                onSourceChosen = onTranslationSourceChosen,
                onTargetChosen = onTranslationTargetChosen,
            )
        }
    }
    if (showDefinitionPanel) {
        AnchoredOverlayBox(selectionRect = selectionRect, webView = selectionSource, viewportWidth = viewportWidth, viewportHeight = viewportHeight, modifier = modifier) {
            AnchoredDefinitionPanel(
                sourceWord = definitionWord ?: selectedText ?: "",
                state = definitionPanelState,
                onDismiss = onDismissDefinition,
                definitionLanguage = definitionLanguage,
                onDefinitionLanguageChosen = { code -> onDefinitionLanguageChosen(code) },
                onAddToDictionary = onAddDefinitionToDictionary,
            )
        }
    }
    if (showColorPickerPopover) {
        // The popover is NOT an `AnchoredOverlayBox` — it offsets itself — so it
        // runs the SAME conversion explicitly rather than being left behind in
        // Readium's space. It is the one surface that must remember to convert;
        // `SelectionOverlayPlacementTest` pins the popover's conversion to the
        // shared one, so a future third producer that diverges fails there.
        val placement = rememberOverlayPlacement(selectionRect, selectionSource)
        val density = LocalDensity.current
        val anchorCenterX = placement.left + (placement.right - placement.left) / 2
        val anchorBelowY = placement.bottom + with(density) { 12.dp.toPx() }.toInt()
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

/**
 * Locates the navigator's WebView for the current composition, or `null` when
 * the selection did not come from one (the PDF reader).
 *
 * `LocalView.current` is the overlay's own content box; the WebView is found by
 * walking the reader screen's hierarchy. `null` is the honest answer for a
 * producer whose rect is already in the overlay's space, and
 * [SelectionOverlaySpace.of] treats it as "no conversion needed".
 */
@Composable
private fun rememberSelectionSource(): android.view.View? {
    val view = LocalView.current
    return remember(view) { ReadiumOverlaySpace.findWebView(view) }
}

/**
 * Runs the one conversion for a surface that does not go through
 * [AnchoredOverlayBox].
 *
 * Used by [HighlightColorPickerPopover], which positions itself with
 * `Modifier.offset` instead of the shared anchor. Every other surface converts
 * inside `AnchoredOverlayBox`; this exists so the popover cannot be the one
 * place the conversion is forgotten.
 *
 * Pure and non-composable on purpose: [overlayPlacementRect] is the arithmetic,
 * and this only adds `remember`. `SelectionOverlayPlacementTest` pins the
 * arithmetic to the same [SelectionOverlaySpace] the shared path uses, so the
 * two cannot drift apart.
 */
@Composable
private fun rememberOverlayPlacement(
    rect: Rect,
    webView: android.view.View?,
): Rect {
    val fallbackOrigin = LocalView.current
    return remember(rect, webView, fallbackOrigin) {
        overlayPlacementRect(rect, webView, fallbackOrigin)
    }
}

/**
 * The popover's conversion, named so it can be tested without composition.
 *
 * It delegates to [SelectionOverlaySpace.of] — the SAME entry point
 * [AnchoredOverlayBox] uses — so the popover's placement is the shared
 * conversion rather than a second, parallel implementation. The returned `rect`
 * is in the overlay's space; [SelectionOverlay] derives `anchorCenterX` /
 * `anchorBelowY` from it.
 *
 * `null` [webView] means the rect is already in the overlay's space (the PDF
 * path), and [SelectionOverlaySpace.of] treats it as "no conversion needed".
 */
internal fun overlayPlacementRect(
    rect: Rect,
    webView: android.view.View?,
    fallbackOrigin: android.view.View,
): Rect = SelectionOverlaySpace.of(rect, webView, fallbackOrigin).rect

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
            onTranslate = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectionOverlayPreview() {
    NexoTheme(darkTheme = true) { SelectionOverlayHost(SelectionSurface.TEXT_MENU) }
}

package com.nexo.ui.components.molecules

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * Floating menu for a NEW text selection:
 * ```
 * [● ● ● ● ●] | [Define] [Translate] [Copy] [Save word] [Share]
 * ```
 * Highlighting is the most common thing to do with a selection, so the five
 * colours are on the pill itself: one tap highlights. They are pinned (see
 * [MenuScrollRow]); on a phone narrower than the whole pill only the actions
 * scroll, so the colours stay side by side and comparable. Actions are ordered
 * by how often a reader reaches for them, Share last because it is the one a
 * narrow screen may half-hide.
 *
 * Define and Translate keep the content descriptions of [FloatingContextMenu]'s
 * Define and Translate byte for byte: one Maestro selector addresses the action
 * in whichever menu is open.
 *
 * @param swatches the five highlight colours, see [highlightPalette]
 * @param onColorSelected highlights the selection in the tapped colour
 * @param onDictionary opens the input that saves the word to the reader's own dictionary
 * @param onDefine looks up the meaning; never writes the personal dictionary
 */
@Composable
fun TextSelectionMenu(
    swatches: List<String>,
    onColorSelected: (String) -> Unit,
    onCopy: () -> Unit,
    onDictionary: () -> Unit,
    onDefine: () -> Unit = {},
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entrance = rememberMenuEntrance()
    MenuScrollRow(
        modifier = modifier.menuEntrance(entrance).selectionMenuPill(),
        leading = {
            swatches.forEach { hex ->
                ColorSwatch(
                    hex = hex,
                    contentDescription = swatchDescription(hex),
                    selected = false,
                    onClick = { onColorSelected(hex) },
                )
            }
            MenuDivider()
        },
        actions = {
            MenuAction(NexoIcons.ReaderDefine, stringResource(R.string.text_selection_define), onDefine)
            MenuAction(NexoIcons.ReaderTranslate, stringResource(R.string.text_selection_translate), onTranslate)
            MenuAction(NexoIcons.ReaderCopy, stringResource(R.string.text_selection_copy), onCopy)
            MenuAction(NexoIcons.ReaderSaveWord, stringResource(R.string.context_menu_dictionary_action), onDictionary)
            MenuAction(NexoIcons.ReaderShare, stringResource(R.string.context_menu_share), onShare)
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun TextSelectionMenuPreview() {
    NexoTheme(darkTheme = true) {
        TextSelectionMenu(
            swatches = HighlightColor.defaultHexList(),
            onColorSelected = {},
            onCopy = {},
            onDictionary = {},
            onDefine = {},
            onTranslate = {},
            onShare = {},
        )
    }
}

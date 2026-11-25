package com.nexo.ui.components.molecules

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * Floating menu for a highlight the reader tapped:
 * ```
 * [● colour] | [Copy] [Define] [Translate] [Tag] [Note] | [Share] [Delete]
 * ```
 * Seven actions leave no room for five inline swatches on a phone, so the
 * leading dot shows the highlight's current colour and, tapped, turns the same
 * pill into the palette:
 * ```
 * [←] [● ● ◉ ● ●] [spectrum]
 * ```
 * with the current colour ringed. A swatch recolours in one tap; the spectrum
 * ring opens the full picker for a colour outside the five.
 *
 * Define and Translate keep [TextSelectionMenu]'s content descriptions byte for
 * byte (one Maestro selector per action across both menus). The colour dot
 * keeps `R.string.context_menu_color`, the trigger's description since before
 * the palette moved inline.
 *
 * @param selectedColor the highlight's current colour (hex, with or without `#`)
 * @param swatches the five highlight colours, see [highlightPalette]
 * @param onColorSelected recolours the highlight
 * @param onMoreColors opens the full colour picker
 */
@Composable
fun FloatingContextMenu(
    selectedColor: String,
    swatches: List<String>,
    onColorSelected: (String) -> Unit,
    onMoreColors: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onTranslate: () -> Unit,
    onAddTag: () -> Unit,
    onAnnotate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entrance = rememberMenuEntrance()
    var choosingColor by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.menuEntrance(entrance).selectionMenuPill()) {
        AnimatedContent(
            targetState = choosingColor,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
            contentAlignment = Alignment.Center,
            label = "highlightMenuMode",
        ) { choosing ->
            if (choosing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MenuAction(
                        icon = NexoIcons.ReaderBack,
                        contentDescription = stringResource(R.string.reader_highlight_colors_back),
                        onClick = { choosingColor = false },
                    )
                    swatches.forEach { hex ->
                        ColorSwatch(
                            hex = hex,
                            contentDescription = swatchDescription(hex),
                            selected = hex.equals(selectedColor, ignoreCase = true),
                            onClick = { onColorSelected(hex) },
                        )
                    }
                    MenuDivider()
                    MoreColorsButton(onClick = onMoreColors)
                }
            } else {
                MenuScrollRow(
                    leading = {
                        ColorSwatch(
                            hex = selectedColor,
                            contentDescription = stringResource(R.string.context_menu_color),
                            selected = true,
                            onClick = { choosingColor = true },
                        )
                        MenuDivider()
                    },
                    actions = {
                        MenuAction(NexoIcons.ReaderCopy, stringResource(R.string.text_selection_copy), onCopy)
                        MenuAction(NexoIcons.ReaderDefine, stringResource(R.string.text_selection_define), onDefine)
                        MenuAction(NexoIcons.ReaderTranslate, stringResource(R.string.text_selection_translate), onTranslate)
                        MenuAction(NexoIcons.ReaderTag, stringResource(R.string.context_menu_tag), onAddTag)
                        MenuAction(NexoIcons.ReaderNote, stringResource(R.string.context_menu_annotate), onAnnotate)
                        MenuDivider()
                        MenuAction(NexoIcons.ReaderShare, stringResource(R.string.context_menu_share), onShare)
                        MenuAction(
                            icon = NexoIcons.ReaderDelete,
                            contentDescription = stringResource(R.string.context_menu_delete),
                            onClick = onDelete,
                            tint = SelectionMenuColors.Destructive,
                        )
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun FloatingContextMenuPreview() {
    NexoTheme(darkTheme = true) {
        FloatingContextMenu(
            selectedColor = HighlightColor.RED.hex,
            swatches = HighlightColor.defaultHexList(),
            onColorSelected = {},
            onMoreColors = {},
            onCopy = {},
            onDefine = {},
            onTranslate = {},
            onAddTag = {},
            onAnnotate = {},
            onShare = {},
            onDelete = {},
        )
    }
}

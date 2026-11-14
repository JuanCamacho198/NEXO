package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Horizontal pill of icon actions shown when the user taps an
 * existing highlight. Sibling of [TextSelectionMenu] but with a
 * different action set: color, copy, define, translate, tag, annotate, share,
 * delete (the "new selection" menu has Dictionary instead of Tag/Delete).
 *
 * Design node `FaPN3` — single pill with thin vertical dividers
 * between action groups:
 * ```
 * [Palette] | [Copy] [Define] [Translate] [Tag] [Annotate] | [Share] [Delete]
 * ```
 * The groups read as *appearance* | *act on the text* | *send or destroy*.
 * Define and Translate sit with the text actions because they consume the same
 * text the highlight already holds; neither opens an input, so neither is
 * grouped with the Palette trigger. Define is placed beside Translate — the two
 * are siblings in intent, a saved highlight being as definable as it is
 * translatable. All colors come from [MaterialTheme.colorScheme] so the
 * menu adapts to light/dark themes.
 *
 * @param selectedColor Currently active highlight color (hex with or
 *   without `#`). Drives the Palette icon tint via
 *   [parseColorHex]. Bad hex strings resolve to opaque black.
 * @param onColorSelected Invoked when the Palette icon is tapped.
 *   Typically opens the [HighlightColorPickerPopover].
 * @param onCopy Copy the highlight text to the clipboard.
 * @param onDefine Look up the MEANING of the highlighted text
 *   ([AnchoredDefinitionPanel]). Mirrors the selection menu's Define:
 *   same callback path, same icon and content description.
 * @param onTranslate Open the [AnchoredTranslationPanel] for the
 *   highlighted text (machine translation, MyMemory).
 * @param onAddTag Open the [AnchoredTagInput] for this highlight.
 * @param onAnnotate Open the [HighlightAnnotationModal] for this
 *   highlight.
 * @param onShare Share the highlight text via the system share sheet.
 * @param onDelete Delete this highlight. The Delete icon is tinted
 *   `colorScheme.error` to signal destructive intent.
 * @param modifier Modifier applied to the outer `Row`.
 *
 * **Visual**: pill-shaped `Row` with 12dp shadow, 6dp padding,
 *   50%-radius corners. Each icon is 40dp clickable circle; vertical
 *   dividers (1dp × 20dp) are placed between the Palette group and the
 *   TextActions group and between TextActions and the Share/Delete group.
 *   Delete uses `colorScheme.error` tint.
 * **Overflow**: the Palette trigger and its divider are pinned; the actions
 *   scroll horizontally when they exceed the width, with an edge fade on any
 *   side that still has content (see [MenuScrollRow]). On a screen wide enough
 *   to fit the whole menu nothing scrolls and no fade is drawn.
 * **Behavior**: each icon calls its respective callback. No internal
 *   state beyond the scroll offset.
 * **Recomposition**: recomposes when `selectedColor` or any callback
 *   changes.
 */
@Composable
fun FloatingContextMenu(
    selectedColor: String,
    onColorSelected: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onTranslate: () -> Unit,
    onAddTag: () -> Unit,
    onAnnotate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val contentColor = contentColorFor(containerColor)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    MenuScrollRow(
        modifier =
            modifier
                .shadow(12.dp, RoundedCornerShape(50))
                .background(containerColor, RoundedCornerShape(50))
                .padding(6.dp),
        leading = {
            SelectionMenuIcon(
                icon = NexoIcons.Palette,
                contentDescription = stringResource(R.string.context_menu_color),
                tint = parseColorHex(selectedColor),
                onClick = onColorSelected,
            )

            MenuVerticalDivider(dividerColor)
        },
        actions = {
            SelectionMenuIcon(
                icon = NexoIcons.Copy,
                contentDescription = stringResource(R.string.text_selection_copy),
                tint = contentColor,
                onClick = onCopy,
            )

            SelectionMenuIcon(
                icon = NexoIcons.BookSearch,
                contentDescription = stringResource(R.string.text_selection_define),
                tint = contentColor,
                onClick = onDefine,
            )

            SelectionMenuIcon(
                icon = NexoIcons.Translate,
                contentDescription = stringResource(R.string.text_selection_translate),
                tint = contentColor,
                onClick = onTranslate,
            )

            SelectionMenuIcon(
                icon = NexoIcons.Tag,
                contentDescription = stringResource(R.string.context_menu_tag),
                tint = contentColor,
                onClick = onAddTag,
            )

            SelectionMenuIcon(
                icon = NexoIcons.Pencil,
                contentDescription = stringResource(R.string.context_menu_annotate),
                tint = contentColor,
                onClick = onAnnotate,
            )

            MenuVerticalDivider(dividerColor)

            SelectionMenuIcon(
                icon = NexoIcons.Share,
                contentDescription = stringResource(R.string.context_menu_share),
                tint = contentColor,
                onClick = onShare,
            )

            SelectionMenuIcon(
                icon = NexoIcons.Trash,
                contentDescription = stringResource(R.string.context_menu_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        },
    )
}

private fun parseColorHex(hex: String): Color =
    try {
        val sanitized = hex.removePrefix("#")
        val longHex =
            when (sanitized.length) {
                6 -> "FF$sanitized"
                8 -> sanitized
                else -> "FF000000"
            }
        Color(longHex.toLong(16))
    } catch (_: Exception) {
        Color.Magenta
    }

@Preview(showBackground = true)
@Composable
private fun FloatingContextMenuDarkPreview() {
    NexoTheme(darkTheme = true) {
        FloatingContextMenu(
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
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

@Preview(showBackground = true)
@Composable
private fun FloatingContextMenuLightPreview() {
    NexoTheme(darkTheme = false) {
        FloatingContextMenu(
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
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

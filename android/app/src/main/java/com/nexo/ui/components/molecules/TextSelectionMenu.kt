package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Floating action menu shown when the user makes a NEW text
 * selection. Sibling of [FloatingContextMenu] but with a different
 * action set: a single palette trigger + copy + dictionary +
 * define + translate + share (the "existing highlight" menu has
 * Tag + Annotate + Delete instead of the dictionary).
 *
 * Design: horizontal pill with thin vertical divider between the
 * colour group and the action group:
 * ```
 * [Palette] | [Copy] [Dictionary] [Define] [Translate] [Share]
 * ```
 * All colors come from [MaterialTheme.colorScheme] so the menu
 * adapts to light/dark themes.
 *
 * **Why one palette trigger and not five inline swatches.** Five swatches
 * consumed ~140dp of the pill; on a 360dp phone the menu overflowed and its
 * ends were cut. Worse, a swatch only means something when you can compare it
 * against its neighbours, so scrolling the palette turns "pick a colour" into
 * "hunt for a colour". The trigger opens [HighlightColorPickerPopover] — the
 * same mechanism [FloatingContextMenu] already uses — which shows every preset
 * at once. The two menus therefore share a colour-control SHAPE without
 * becoming one menu.
 *
 * @param selectedColor Currently active highlight color (hex with
 *   or without `#`). Drives the Palette icon tint via
 *   [parseColorHex] so the user sees the last-used colour. Pass
 *   `null` to fall back to the neutral content colour.
 * @param onColorSelected Invoked when the Palette icon is tapped.
 *   Typically opens the [HighlightColorPickerPopover]; the
 *   popover's own colour callback does the actual highlight.
 * @param onCopy Copy the selected text to the clipboard.
 * @param onDictionary Open the [AnchoredDefinitionInput] for the
 *   selected word (the PERSONAL dictionary — save a word). The content
 *   description is `R.string.context_menu_dictionary_action` ("Dictionary" /
 *   "Diccionario") — a short, stable, locale-independent selector, distinct from
 *   the longer `R.string.context_menu_dictionary` label.
 * @param onDefine Look up the MEANING of the selected word
 *   ([AnchoredDefinitionPanel]). A different action from
 *   [onDictionary]: this one looks up, it never writes the personal
 *   dictionary. The icon is [NexoIcons.BookSearch] and the content
 *   description `R.string.text_selection_define` — BOTH must stay byte-identical
 *   to [FloatingContextMenu]'s Define, because one Maestro selector addresses the
 *   action in either menu. See [NexoIcons.BookSearch] for why that glyph is what
 *   it is.
 * @param onTranslate Open the [AnchoredTranslationPanel] for the
 *   selected word (machine translation, MyMemory). The icon is
 *   [NexoIcons.Translate] and the content description
 *   `R.string.text_selection_translate` — BOTH must stay byte-identical to
 *   [FloatingContextMenu]'s Translate, because one Maestro selector
 *   (`"Translate|Traducir"`) addresses the action in either menu, the same
 *   contract Define carries. [NexoIcons.Translate] is the translation ACTION
 *   glyph (see [NexoIcons.Translate] and the Settings translation row);
 *   [NexoIcons.Language] means "language selection" and is not this action.
 * @param onShare Share the selected text via the system share
 *   sheet.
 * @param modifier Modifier applied to the outer `Row`.
 *
 * **Visual**: pill-shaped `Row` with 8dp shadow, 6dp padding,
 *   50%-radius corners. Action icons are 40dp clickable circles (same as
 *   [FloatingContextMenu]). A vertical divider (1dp × 20dp) sits between the
 *   palette trigger and the action group.
 * **Overflow**: the palette trigger is pinned; the actions scroll horizontally
 *   when they exceed the width, with an edge fade on any side that still has
 *   content (see [MenuScrollRow]). On a screen wide enough to fit the whole
 *   menu nothing scrolls and no fade is drawn.
 * **Behavior**: the palette trigger calls [onColorSelected]; each action icon
 *   calls its respective callback. No internal state beyond the scroll offset.
 * **Recomposition**: recomposes when `selectedColor` or any callback changes.
 */
@Composable
fun TextSelectionMenu(
    selectedColor: String? = null,
    onColorSelected: () -> Unit,
    onCopy: () -> Unit,
    onDictionary: () -> Unit,
    onDefine: () -> Unit = {},
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val contentColor = contentColorFor(containerColor)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    MenuScrollRow(
        modifier =
            modifier
                .shadow(8.dp, RoundedCornerShape(50))
                .background(containerColor, RoundedCornerShape(50))
                .padding(6.dp),
        leading = {
            SelectionMenuIcon(
                icon = NexoIcons.Palette,
                contentDescription = stringResource(R.string.context_menu_color),
                tint = selectedColor?.let(::parseColorHex) ?: contentColor,
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
                icon = NexoIcons.BookOpen,
                contentDescription = stringResource(R.string.context_menu_dictionary_action),
                tint = contentColor,
                onClick = onDictionary,
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
                icon = NexoIcons.Share,
                contentDescription = stringResource(R.string.context_menu_share),
                tint = contentColor,
                onClick = onShare,
            )
        },
    )
}

@Composable
internal fun SelectionMenuIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun MenuVerticalDivider(color: Color) {
    Box(
        modifier =
            Modifier
                .padding(horizontal = 2.dp)
                .width(1.dp)
                .size(20.dp)
                .background(color),
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
private fun TextSelectionMenuDarkPreview() {
    NexoTheme(darkTheme = true) {
        TextSelectionMenu(
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
            onCopy = {},
            onDictionary = {},
            onDefine = {},
            onTranslate = {},
            onShare = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TextSelectionMenuLightPreview() {
    NexoTheme(darkTheme = false) {
        TextSelectionMenu(
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
            onCopy = {},
            onDictionary = {},
            onDefine = {},
            onTranslate = {},
            onShare = {},
        )
    }
}

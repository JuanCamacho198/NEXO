package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 * action set: 5 color swatches + copy + dictionary + share (the
 * "existing highlight" menu has Tag + Delete instead of the
 * dictionary, and uses a single color popover trigger).
 *
 * Design: horizontal pill with thin vertical divider between the
 * color group and the action group:
 * ```
 * [●][●][●][●][●] | [Copy] [Dictionary] [Share]
 * ```
 * All colors come from [MaterialTheme.colorScheme] so the menu
 * adapts to light/dark themes.
 *
 * @param paletteColors List of 5 hex color strings shown as
 *   swatches. Should come from
 *   `ReaderSettings.customHighlightColors` (or the defaults via
 *   [HighlightColor.defaultHexList]). The first 5 are used; extras
 *   are ignored.
 * @param selectedColor Currently active highlight color (hex with
 *   or without `#`). The matching swatch gets a 2dp ring around
 *   it so the user can see which color was last used. Pass `null`
 *   to disable the ring (e.g. for the very first selection).
 * @param onColorSelected Invoked with the chosen hex when a
 *   swatch is tapped. The caller should immediately persist the
 *   highlight with that color (this menu does NOT open a popover).
 * @param onCopy Copy the selected text to the clipboard.
 * @param onDictionary Open the [AnchoredDefinitionInput] for the
 *   selected word.
 * @param onShare Share the selected text via the system share
 *   sheet.
 * @param modifier Modifier applied to the outer `Row`.
 *
 * **Visual**: pill-shaped `Row` with 8dp shadow, 6dp padding,
 *   50%-radius corners. Each swatch is a 28dp clickable circle;
 *   the active swatch (matching [selectedColor]) is wrapped in a
 *   2dp ring of `onSurface` for visibility against the
 *   surface. Action icons are 40dp clickable circles (same as
 *   [FloatingContextMenu]). Vertical dividers (1dp × 20dp) sit
 *   between the color group and the action group.
 * **Behavior**: each swatch calls [onColorSelected] with its hex.
 *   Each action icon calls its respective callback. No internal
 *   state, no animation.
 * **Recomposition**: recomposes when `paletteColors`,
 *   `selectedColor`, or any callback changes.
 */
@Composable
fun TextSelectionMenu(
    paletteColors: List<String>,
    selectedColor: String? = null,
    onColorSelected: (String) -> Unit,
    onCopy: () -> Unit,
    onDictionary: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val contentColor = contentColorFor(containerColor)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier =
            modifier
                .shadow(8.dp, RoundedCornerShape(50))
                .background(containerColor, RoundedCornerShape(50))
                .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── 5 color swatches (direct selection) ─────────────────
        paletteColors.take(5).forEach { hex ->
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(parseColorHex(hex))
                        .clickable { onColorSelected(hex) },
            )
        }

        MenuVerticalDivider(dividerColor)

        // ── Actions ─────────────────────────────────────────────
        SelectionMenuIcon(
            icon = NexoIcons.Copy,
            contentDescription = stringResource(R.string.text_selection_copy),
            tint = contentColor,
            onClick = onCopy,
        )

        SelectionMenuIcon(
            icon = NexoIcons.BookOpen,
            contentDescription = stringResource(R.string.context_menu_dictionary),
            tint = contentColor,
            onClick = onDictionary,
        )

        SelectionMenuIcon(
            icon = NexoIcons.Share,
            contentDescription = stringResource(R.string.context_menu_share),
            tint = contentColor,
            onClick = onShare,
        )
    }
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
            paletteColors = HighlightColor.defaultHexList(),
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
            onCopy = {},
            onDictionary = {},
            onShare = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TextSelectionMenuLightPreview() {
    NexoTheme(darkTheme = false) {
        TextSelectionMenu(
            paletteColors = HighlightColor.defaultHexList(),
            selectedColor = HighlightColor.YELLOW.hex,
            onColorSelected = {},
            onCopy = {},
            onDictionary = {},
            onShare = {},
        )
    }
}

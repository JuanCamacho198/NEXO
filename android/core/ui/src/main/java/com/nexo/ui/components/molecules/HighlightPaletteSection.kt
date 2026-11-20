package com.nexo.ui.components.molecules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.components.molecules.highlight.rememberColorPickerState
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Settings section that displays the five customizable highlight
 * color swatches and lets the user edit each one or reset to
 * defaults. Each swatch row opens an inline `AlertDialog` for
 * entering a hex code.
 *
 * @param customColors Current 5-color palette as hex strings, or
 *   `null` to show the [HighlightColor] enum defaults. Exactly five
 *   entries are expected; extras are ignored, fewer than five show
 *   fewer rows.
 * @param onUpdateColor Invoked with `(index, "#RRGGBB")` when the
 *   user confirms a swatch edit. Called only for valid 6-char hex
 *   strings (the dialog validates and silently rejects invalid
 *   input on save).
 * @param onReset Invoked when the user taps the "Reset to defaults"
 *   row. The caller is expected to clear the custom palette (and
 *   `customColors` will then be `null` on the next composition).
 * @param modifier Modifier applied to the outer `Column`.
 *
 * **Visual**: subtitle in `bodySmall` `onSurfaceVariant`, then 5
 *   `PaletteSwatchRow`s (32dp circle + uppercase hex, 12dp rounded
 *   `surfaceVariant` background), then a full-width "Reset to
 *   defaults" row tinted `colorScheme.error`. 16dp horizontal
 *   padding, 8dp vertical spacing between rows.
 * **Behavior**: tap a swatch → opens an `AlertDialog` with a color
 *   preview circle and a single-line hex input. Confirming a valid
 *   6-char hex calls [onUpdateColor(index, hex)]. Tapping the
 *   reset row calls [onReset] immediately (no confirmation).
 *   Internal `editingIndex`/`editorHex` state is `remember`-ed and
 *   survives recomposition but not process death.
 * **Recomposition**: recomposes when `customColors` or callbacks
 *   change.
 */
@Composable
fun HighlightPaletteSection(
    customColors: List<String>?,
    onUpdateColor: (Int, String) -> Unit,
    onAddColor: () -> Unit,
    onDeleteColor: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    addColorLabel: String? = null,
    resetLabel: String? = null,
    pickColorLabel: String? = null,
    cancelLabel: String? = null,
    deleteContentDescription: String? = null,
) {
    val colors = customColors ?: HighlightColor.defaultHexList()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editorHex by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Preview strip: the palette at a glance ──────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp)),
        ) {
            colors.forEach { hex ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(parseColorHex(hex)))
            }
        }

        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = nexoColors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // ── Swatch rows, one card ───────────────────────────────────
        NexoSettingsSection {
            colors.forEachIndexed { index, hex ->
                if (index > 0) NexoSettingsDivider()
                PaletteSwatchRow(
                    index = index,
                    hex = hex,
                    canDelete = colors.size > 3,
                    onDelete = onDeleteColor,
                    deleteContentDescription = deleteContentDescription.orEmpty(),
                    onClick = {
                        editingIndex = index
                        editorHex = hex
                    },
                )
            }
            // ── Add color, last row of the same card ──────────────────
            if (colors.size < 5) {
                NexoSettingsDivider()
                NexoSettingsNavRow(
                    label = addColorLabel.orEmpty(),
                    icon = NexoIcons.Add,
                    onClick = onAddColor,
                    trailing = {},
                )
            }
        }

        // ── Reset: outlined, red — it discards the custom palette ───
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        ) {
            Text(
                text = resetLabel.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // ── Color picker dialog per swatch ──────────────────────────
    val selectedEditingIndex = editingIndex
    if (selectedEditingIndex != null) {
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = {
                Text(text = pickColorLabel.orEmpty())
            },
            text = {
                ColorPickerContent(
                    presets = colors,
                    state = rememberColorPickerState(editorHex),
                    onColorSelected = { hex ->
                        onUpdateColor(selectedEditingIndex, hex)
                        editingIndex = null
                    },
                    onDismiss = { editingIndex = null },
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) {
                    Text(text = cancelLabel.orEmpty())
                }
            },
        )
    }
}

/** Single row: a large swatch + its hex code + optional delete, tappable to edit. */
@Composable
private fun PaletteSwatchRow(
    index: Int,
    hex: String,
    canDelete: Boolean,
    onDelete: (Int) -> Unit,
    onClick: () -> Unit,
    deleteContentDescription: String = "",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(parseColorHex(hex))
                    .border(1.dp, nexoColors.borderSubtle, RoundedCornerShape(10.dp)),
        )
        Text(
            text = hex.uppercase(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = nexoColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (canDelete) {
            IconButton(onClick = { onDelete(index) }) {
                Icon(
                    NexoIcons.Close,
                    contentDescription = deleteContentDescription,
                    tint = nexoColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/** Parses a hex colour string (with or without #) to a Compose [Color]. */
@Composable
private fun parseColorHex(hex: String): Color {
    val sanitized = hex.removePrefix("#").trim()
    val longHex =
        when (sanitized.length) {
            6 -> "FF$sanitized"
            8 -> sanitized
            else -> "FF000000"
        }
    return try {
        Color(longHex.toLong(16))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.outline
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightPaletteSectionDarkPreview() {
    NexoTheme(darkTheme = true) {
        HighlightPaletteSection(
            customColors = HighlightColor.defaultHexList(),
            onUpdateColor = { _, _ -> },
            onAddColor = {},
            onDeleteColor = {},
            onReset = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightPaletteSectionLightPreview() {
    NexoTheme(darkTheme = false) {
        HighlightPaletteSection(
            customColors = HighlightColor.defaultHexList(),
            onUpdateColor = { _, _ -> },
            onAddColor = {},
            onDeleteColor = {},
            onReset = {},
        )
    }
}

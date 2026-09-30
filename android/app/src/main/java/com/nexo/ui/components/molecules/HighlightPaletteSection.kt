package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.highlight.rememberColorPickerState
import com.nexo.ui.icons.NexoIcons

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
) {
    val colors = customColors ?: HighlightColor.defaultHexList()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editorHex by remember { mutableStateOf("") }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Subtitle ──────────────────────────────────────────────
        Text(
            text = stringResource(R.string.palette_section_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Swatch rows ──────────────────────────────────────────
        colors.forEachIndexed { index, hex ->
            PaletteSwatchRow(
                index = index,
                hex = hex,
                canDelete = colors.size > 3,
                onDelete = onDeleteColor,
                onClick = {
                    editingIndex = index
                    editorHex = hex
                },
            )
        }

        // ── Add color button ────────────────────────────────────
        if (colors.size < 5) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddColor),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        NexoIcons.Add,
                        contentDescription = stringResource(R.string.palette_add_color),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.palette_add_color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── Reset button ──────────────────────────────────────────
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReset),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.palette_reset),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }

    // ── Color picker dialog per swatch ──────────────────────────
    val selectedEditingIndex = editingIndex
    if (selectedEditingIndex != null) {
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = {
                Text(text = stringResource(R.string.palette_pick_color))
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
                    Text(text = stringResource(R.string.reader_cancel))
                }
            },
        )
    }
}

/** Single row: coloured circle + hex label + optional delete, tappable to edit. */
@Composable
private fun PaletteSwatchRow(
    index: Int,
    hex: String,
    canDelete: Boolean,
    onDelete: (Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(parseColorHex(hex))
                        .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = hex.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (canDelete) {
                IconButton(onClick = { onDelete(index) }) {
                    Icon(
                        NexoIcons.Close,
                        contentDescription = stringResource(R.string.annotation_modal_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
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

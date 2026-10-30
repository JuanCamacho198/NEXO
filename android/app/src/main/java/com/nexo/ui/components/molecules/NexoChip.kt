package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoTheme

/** Horizontal inset inside a chip. Matches the `.pen` chip specimen. */
private val ChipHorizontalPadding = 14.dp

/** Vertical inset inside a chip. Matches the `.pen` chip specimen. */
private val ChipVerticalPadding = 8.dp

/** Side length of the optional leading icon. */
private val ChipIconSize = 14.dp

/** Gap between the optional leading icon and the label. */
private val ChipIconGap = 6.dp

/**
 * The one filter/selection chip for NEXO.
 *
 * Three separate chip implementations previously disagreed on shape and
 * colour role: `NexoFilterTabs` used a solid `primary` fill, `DiscoverChipRow`
 * used `primary` at 18% alpha, and `MetadataChips` used `secondaryContainer`
 * on an 8dp radius. This component takes the geometry both the `.pen` specimen
 * and the majority of call sites agree on — a pill, 14dp × 8dp — and the
 * `primary` selection fill, so a selected chip means the same thing everywhere.
 *
 * [NexoColors.surface] is the resting fill and `primary` is the selected one;
 * `secondaryContainer` is deliberately not accepted here because it belongs to
 * editable metadata, not to filters.
 *
 * @param label Chip label. Already localized by the caller.
 * @param selected Whether this chip is the active filter.
 * @param onClick Invoked on tap.
 * @param modifier Modifier applied to the chip row.
 * @param icon Optional leading glyph, tinted `primary` in both states so the
 *   icon never competes with the label for selection signalling.
 * @param contentDescription Accessibility label. Null falls back to [label],
 *   which is the correct default for a text chip.
 *
 * **Behavior**: no internal state; [selected] is hoisted.
 * **Recomposition**: recomposes when [selected], [label] or [icon] change.
 */
@Composable
fun NexoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    containerColor: Color = if (selected) NexoColors.primary else NexoColors.surface,
    iconTint: Color = if (selected) NexoColors.surface else NexoColors.primary,
    labelColor: Color = if (selected) NexoColors.surface else NexoColors.textPrimary,
) {
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(containerColor)
                .clickable(onClickLabel = contentDescription ?: label, onClick = onClick)
                .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChipIconGap),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ChipIconSize),
                tint = iconTint,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoChipDarkPreview() {
    NexoTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoChip(label = "Todos", selected = true, onClick = {})
            NexoChip(label = "Gutenberg", selected = false, onClick = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoChipLightPreview() {
    NexoTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoChip(label = "Todos", selected = true, onClick = {})
            NexoChip(label = "Gutenberg", selected = false, onClick = {})
        }
    }
}

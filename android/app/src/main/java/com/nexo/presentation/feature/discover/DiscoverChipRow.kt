package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors

/**
 * One generic pill in a [DiscoverChipRow]. Labels are already localized by the
 * caller so the row stays reusable across trending, suggestion, source-filter,
 * language and format chips.
 */
data class DiscoverChip(
    val label: String,
    val icon: ImageVector? = null,
    val selected: Boolean = false,
)

/**
 * Horizontally scrolling row of pill chips (radius 999, `bg_surface`, optional
 * `primary` icon + label). Scrolls horizontally without changing the vertical
 * layout of the screen.
 *
 * @param chipBackground Fill for an unselected chip. Defaults to `bg_surface`;
 *   the detail sheet passes `bg_card_hover` per the design.
 * @param chipLabelColor Label tint for an unselected chip.
 */
@Composable
fun DiscoverChipRow(
    chips: List<DiscoverChip>,
    onChipClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
    chipBackground: Color = NexoColors.surface,
    chipLabelColor: Color = NexoColors.textSecondary,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(chips, key = { index, chip -> "$index:${chip.label}" }) { index, chip ->
            val background =
                if (chip.selected) {
                    NexoColors.primary.copy(alpha = 0.18f)
                } else {
                    chipBackground
                }
            val labelColor =
                if (chip.selected) {
                    NexoColors.primary
                } else {
                    chipLabelColor
                }
            Row(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(background)
                        .clickable { onChipClick(index) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chip.icon != null) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = NexoColors.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = chip.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = labelColor,
                )
            }
        }
    }
}

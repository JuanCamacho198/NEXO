package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nexo.ui.components.molecules.NexoChip

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
 * Horizontally scrolling row of pill chips. Delegates the chip itself to the
 * shared [NexoChip], so trending, suggestion, source-filter, language and
 * format chips all share one shape, one resting fill and one selection rule
 * with the rest of the app.
 *
 * Call sites no longer pass a custom resting fill: the shared chip owns it.
 */
@Composable
fun DiscoverChipRow(
    chips: List<DiscoverChip>,
    onChipClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(chips, key = { index, chip -> "$index:${chip.label}" }) { index, chip ->
            NexoChip(
                label = chip.label,
                selected = chip.selected,
                onClick = { onChipClick(index) },
                icon = chip.icon,
            )
        }
    }
}

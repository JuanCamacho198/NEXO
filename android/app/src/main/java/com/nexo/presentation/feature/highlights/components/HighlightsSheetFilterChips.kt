package com.nexo.presentation.feature.highlights.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HighlightsSheetFilterChips(
    colorFilterLabels: List<Pair<String?, String>>,
    selectedColorFilter: String?,
    onFilterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colorFilterLabels.forEach { (filterValue, label) ->
            val isSelected = selectedColorFilter == filterValue
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filterValue) },
                label = {
                    ColorFilterCircle(
                        filterValue = filterValue,
                        isSelected = isSelected,
                    )
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent,
                    ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

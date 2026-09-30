package com.nexo.ui.components.molecules

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme

data class FilterTab(
    val id: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector? = null,
)

/**
 * Horizontally scrolling row of pill-shaped filter tabs. The active
 * tab uses `colorScheme.primary`; inactive tabs use
 * `colorScheme.surfaceVariant`. Optionally shows a ">" indicator on
 * the right edge when more tabs are available to the right (i.e.
 * there is horizontal overflow in the scroll position).
 *
 * @param tabs Ordered list of tabs. Each [FilterTab] is `id` +
 *   localized label (and optional icon).
 * @param selectedTabId Id of the active tab. Must match one of
 *   `tabs[*].id`; no tab is highlighted if the id is unknown.
 * @param onTabSelected Invoked with the tapped tab's id.
 * @param modifier Modifier applied to the outer `Box`.
 * @param showOverflowIndicator `true` (default) to show a ">" hint
 *   on the right edge when more tabs are scrollable. Set `false` to
 *   hide it (e.g. when the parent already shows a different scroll
 *   affordance).
 *
 * **Visual**: 20dp-pill `Surface` per tab, 14dp horizontal × 8dp
 *   vertical padding. Active tab: `primary` background,
 *   `onPrimary` text, semibold weight. Inactive tab:
 *   `surfaceVariant` background, `onSurfaceVariant` text, normal
 *   weight. Optional icon (16dp) before the label. The overflow
 *   indicator is a `titleMedium` ">" in `colorScheme.primary` at
 *   the right-center, with 8dp end padding.
 * **Behavior**: tap a tab → [onTabSelected(tab.id)`. The horizontal
 *   scroll state is owned internally; the overflow indicator is
 *   derived from `ScrollState.canScrollForward` via
 *   `derivedStateOf`.
 * **Recomposition**: recomposes when `tabs`, `selectedTabId`, or
 *   callbacks change; the overflow indicator recomposes only when
 *   the scroll forward-ability flips.
 */
@Composable
fun NexoFilterTabs(
    tabs: List<FilterTab>,
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    showOverflowIndicator: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val canScrollForward by remember { derivedStateOf { scrollState.canScrollForward } }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val isSelected = selectedTabId == tab.id
                Surface(
                    onClick = { onTabSelected(tab.id) },
                    shape = RoundedCornerShape(20.dp),
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    contentColor =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tab.icon?.let { icon ->
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (showOverflowIndicator && canScrollForward) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
            ) {
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoFilterTabsDarkPreview() {
    NexoTheme(darkTheme = true) {
        val tabs =
            listOf(
                FilterTab("all", R.string.library_tab_all),
                FilterTab("reading", R.string.library_tab_reading),
                FilterTab("pending", R.string.library_tab_pending),
                FilterTab("completed", R.string.library_tab_completed),
            )

        NexoFilterTabs(
            tabs = tabs,
            selectedTabId = "reading",
            onTabSelected = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoFilterTabsLightPreview() {
    NexoTheme(darkTheme = false) {
        val tabs =
            listOf(
                FilterTab("all", R.string.library_tab_all),
                FilterTab("reading", R.string.library_tab_reading),
                FilterTab("pending", R.string.library_tab_pending),
                FilterTab("completed", R.string.library_tab_completed),
            )

        NexoFilterTabs(
            tabs = tabs,
            selectedTabId = "reading",
            onTabSelected = {},
        )
    }
}

package com.nexo.ui.components.molecules

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

private val TabHeight = 36.dp
private const val TabColorMs = 220

private val LibraryStatusTabs =
    listOf(
        FilterTab("all", R.string.library_tab_all),
        FilterTab("reading", R.string.library_tab_reading),
        FilterTab("pending", R.string.library_tab_pending),
        FilterTab("completed", R.string.library_tab_completed),
    )

/**
 * The four library status tabs: All, Reading, Pending, Completed.
 *
 * @param selectedTab Id of the active tab (`"all"`, `"reading"`, `"pending"`,
 *   `"completed"`). An unknown id leaves no tab selected.
 * @param onTabSelected Invoked with the tapped tab's id.
 */
@Composable
fun StatusChipRow(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NexoPillTabs(
        tabs = LibraryStatusTabs,
        selectedTabId = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
    )
}

/**
 * Horizontally scrolling pill tabs: outlined when idle, filled `primary` when
 * active, with the colour change animated so switching a filter reads as one
 * smooth state change instead of a jump. Optional per-tab glyph.
 *
 * @param contentPadding Horizontal inset of the first and last pill; the row
 *   itself is full-bleed so the pills scroll under the screen edge.
 */
@Composable
fun NexoPillTabs(
    tabs: List<FilterTab>,
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            PillTab(
                label = stringResource(tab.labelRes),
                icon = tab.icon,
                selected = tab.id == selectedTabId,
                onClick = { if (tab.id != selectedTabId) onTabSelected(tab.id) },
            )
        }
    }
}

@Composable
private fun PillTab(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = nexoColors
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.surface,
        animationSpec = tween(TabColorMs),
        label = "tabContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else colors.textSecondary,
        animationSpec = tween(TabColorMs),
        label = "tabContent",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.borderSubtle,
        animationSpec = tween(TabColorMs),
        label = "tabStroke",
    )
    Row(
        modifier =
            Modifier
                .height(TabHeight)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, stroke, CircleShape)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusChipRowDarkPreview() {
    NexoTheme(darkTheme = true) {
        StatusChipRow(
            selectedTab = "reading",
            onTabSelected = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusChipRowLightPreview() {
    NexoTheme(darkTheme = false) {
        StatusChipRow(
            selectedTab = "reading",
            onTabSelected = {},
        )
    }
}

package com.nexo.ui.components.molecules

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.nexoColors

/*
 * Settings kit: the building blocks every settings sub-page is made of, so they
 * all share the main Settings list's look — a small heading over ONE rounded card
 * per group, rows separated by inset hairlines, a tinted icon tile per row.
 *
 * Rows are full-width touch targets (min 56dp) whose whole surface toggles or
 * selects; the trailing Switch / check only mirrors the state.
 */

private val SectionShape = RoundedCornerShape(18.dp)
private val RowMinHeight = 56.dp
private val IconTile = 34.dp
private val RowHorizontalPadding = 14.dp

/** Divider inset that clears the icon tile, so the hairline starts under the label. */
private val DividerInsetWithIcon = 62.dp
private const val SelectAnimMs = 180

/**
 * A settings group: optional small heading, a single card holding [content], and
 * an optional footnote under it. Put rows inside and separate them with
 * [NexoSettingsDivider].
 */
@Composable
fun NexoSettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = nexoColors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(SectionShape)
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, SectionShape),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Hairline between rows of a [NexoSettingsSection]. */
@Composable
fun NexoSettingsDivider(inset: Boolean = true) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = if (inset) DividerInsetWithIcon else RowHorizontalPadding)
                .height(1.dp)
                .background(nexoColors.borderSubtle),
    )
}

/** Rounded tile holding a row glyph, tinted with [accent]. */
@Composable
fun NexoSettingsIcon(
    icon: ImageVector,
    accent: Color = nexoColors.primary,
) {
    Box(
        modifier =
            Modifier
                .size(IconTile)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowScope.RowLabels(
    label: String,
    supporting: String?,
    labelColor: Color,
    bold: Boolean,
) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = nexoColors.textSecondary,
            )
        }
    }
}

private fun Modifier.settingsRowPadding(): Modifier =
    this
        .fillMaxWidth()
        .heightIn(min = RowMinHeight)

/**
 * One choice in a single-choice group. The selected row is tinted, its label
 * bold `primary`, and a filled check pops in at the end.
 */
@Composable
fun NexoSettingsOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    supporting: String? = null,
    accent: Color = nexoColors.primary,
) {
    val colors = nexoColors
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(SelectAnimMs),
        label = "optionRow",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "optionCheck",
    )
    Row(
        modifier =
            Modifier
                .settingsRowPadding()
                .background(container)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) NexoSettingsIcon(icon = icon, accent = accent)
        RowLabels(label = label, supporting = supporting, labelColor = if (selected) colors.primary else colors.textPrimary, bold = selected)
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    }.clip(CircleShape)
                    .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = NexoIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

/** An on/off setting; the whole row toggles. */
@Composable
fun NexoSettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    accent: Color = nexoColors.primary,
) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .settingsRowPadding()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) NexoSettingsIcon(icon = icon, accent = accent)
        RowLabels(label = label, supporting = supporting, labelColor = if (enabled) colors.textPrimary else colors.textSecondary, bold = false)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that opens something or runs an action: icon, label, optional value and
 * a chevron. [destructive] paints label and icon in the error colour and drops
 * the chevron. With [onClick] null the row is informational and inert.
 */
@Composable
fun NexoSettingsNavRow(
    label: String,
    onClick: (() -> Unit)?,
    icon: ImageVector? = null,
    value: String? = null,
    supporting: String? = null,
    destructive: Boolean = false,
    accent: Color = nexoColors.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = nexoColors
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier =
            Modifier
                .settingsRowPadding()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) NexoSettingsIcon(icon = icon, accent = if (destructive) error else accent)
        RowLabels(label = label, supporting = supporting, labelColor = if (destructive) error else colors.textPrimary, bold = destructive)
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            trailing != null -> trailing()
            onClick != null && !destructive ->
                Icon(imageVector = NexoIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

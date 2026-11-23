package com.nexo.ui.components.molecules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.nexo.ui.theme.nexoColors

private val MenuShape = RoundedCornerShape(16.dp)
private val MenuMinWidth = 208.dp

/**
 * The app's dropdown menu: Material's [DropdownMenu] on the theme surface with
 * 16dp corners, a hairline border and a soft shadow, instead of the stock square,
 * tonal menu that looked foreign next to the rest of the UI.
 */
@Composable
fun NexoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = nexoColors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = MenuMinWidth),
        offset = offset,
        shape = MenuShape,
        containerColor = colors.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, colors.borderSubtle),
        content = content,
    )
}

/**
 * One menu row: a tinted glyph and its label. [destructive] paints both in the
 * error colour — reserved for actions that remove something.
 */
@Composable
fun NexoDropdownMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = nexoColors
    val tint = if (destructive) MaterialTheme.colorScheme.error else colors.textSecondary
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error else colors.textPrimary,
            )
        },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(48.dp),
    )
}

/** Hairline separator between menu groups, inset from the menu's edges. */
@Composable
fun NexoDropdownMenuDivider() {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(nexoColors.borderSubtle),
    )
}

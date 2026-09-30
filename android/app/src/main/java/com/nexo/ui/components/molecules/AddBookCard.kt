package com.nexo.ui.components.molecules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Placeholder card shown in the library grid/list to trigger the
 * "import a book" flow. A 280dp-tall box with a dashed 12dp rounded
 * border, a centered add icon, and two short labels.
 *
 * @param onImportClick Invoked when the user taps anywhere on the
 *   card. The caller is expected to launch the file picker.
 *
 * **Visual**: full-width 280dp box. Dashed border (`outline` at 50%
 *   alpha, 1.5dp stroke, 8dp/4dp dash/gap pattern) clipped to a 12dp
 *   rounded shape. Centered column: 40dp `Add` icon in `primary`,
 *   `bodyMedium` primary label (`R.string.library_import_book`),
 *   `bodySmall` muted subtitle (`R.string.library_import_formats`),
 *   all with 8dp vertical spacing.
 * **Behavior**: tap anywhere on the card → [onImportClick]. The
 *   whole box is the touch target. No internal state.
 * **Recomposition**: recomposes only when [onImportClick] changes.
 */

/**
 * Placeholder card shown in the library grid/list to trigger the
 * "import a book" flow. A 280dp-tall box with a dashed 12dp rounded
 * border, a centered add icon, and two short labels.
 *
 * @param onImportClick Invoked when the user taps anywhere on the
 *   card. The caller is expected to launch the file picker.
 *
 * **Visual**: full-width 280dp box. Dashed border (`outline` at 50%
 *   alpha, 1.5dp stroke, 8dp/4dp dash/gap pattern) clipped to a 12dp
 *   rounded shape. Centered column: 40dp `Add` icon in `primary`,
 *   `bodyMedium` primary label (`R.string.library_import_book`),
 *   `bodySmall` muted subtitle (`R.string.library_import_formats`),
 *   all with 8dp vertical spacing.
 * **Behavior**: tap anywhere on the card → [onImportClick]. The
 *   whole box is the touch target. No internal state.
 * **Recomposition**: recomposes only when [onImportClick] changes.
 */
@Composable
fun AddBookCard(
    onImportClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(12.dp))
                .dashedBorder(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    strokeWidth = 1.5.dp,
                    cornerRadius = 12.dp,
                    dashLength = 8.dp,
                    gapLength = 4.dp,
                ).clickable { onImportClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = NexoIcons.Add,
                contentDescription = stringResource(R.string.library_import_book),
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.library_import_book),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.library_import_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddBookCardDarkPreview() {
    NexoTheme(darkTheme = true) {
        AddBookCard(onImportClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AddBookCardLightPreview() {
    NexoTheme(darkTheme = false) {
        AddBookCard(onImportClick = {})
    }
}

private fun Modifier.dashedBorder(
    color: Color,
    strokeWidth: Dp = 1.dp,
    cornerRadius: Dp = 12.dp,
    dashLength: Dp = 8.dp,
    gapLength: Dp = 4.dp,
) = this.drawBehind {
    val rect =
        androidx.compose.ui.geometry.Rect(
            offset = androidx.compose.ui.geometry.Offset.Zero,
            size = size,
        )
    val path =
        androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = rect,
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(cornerRadius.toPx()),
                ),
            )
        }
    drawPath(
        path = path,
        color = color,
        style =
            Stroke(
                width = strokeWidth.toPx(),
                pathEffect =
                    PathEffect.dashPathEffect(
                        floatArrayOf(dashLength.toPx(), gapLength.toPx()),
                        0f,
                    ),
            ),
    )
}

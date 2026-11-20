package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * Card in the library grid/list that starts the "import a book" flow: a dashed,
 * faintly `primary`-tinted surface with an add glyph and two short labels.
 *
 * @param onImportClick Invoked when the user taps anywhere on the card. The
 *   caller is expected to launch the file picker.
 * @param isGridView `true` draws it as a 2:3 book in the grid; `false` as a
 *   76dp row in the list.
 */
@Composable
fun AddBookCard(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
    isGridView: Boolean = true,
) {
    val label = stringResource(R.string.library_import_book)
    val shape = RoundedCornerShape(if (isGridView) 12.dp else 16.dp)
    val base =
        modifier
            .fillMaxWidth()
            // Same 2:3 shape as a grid cover, so the card sits in the shelf as one
            // more book; a slim row in the list view.
            .then(if (isGridView) Modifier.aspectRatio(2f / 3f) else Modifier.height(ListRowHeight))
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = FillAlpha))
            .dashedBorder(
                color = MaterialTheme.colorScheme.primary.copy(alpha = StrokeAlpha),
                strokeWidth = 1.5.dp,
                cornerRadius = if (isGridView) 12.dp else 16.dp,
                dashLength = 8.dp,
                gapLength = 5.dp,
            ).clickable(onClickLabel = label, onClick = onImportClick)
    if (isGridView) {
        Column(
            modifier = base.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            AddGlyph()
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.library_import_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Row(
            modifier = base.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AddGlyph()
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
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
}

@Composable
private fun AddGlyph() {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = GlyphAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NexoIcons.Add,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private val ListRowHeight = 76.dp
private const val FillAlpha = 0.06f
private const val StrokeAlpha = 0.45f
private const val GlyphAlpha = 0.16f

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

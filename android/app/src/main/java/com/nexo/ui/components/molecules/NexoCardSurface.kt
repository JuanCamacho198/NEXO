package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme

/**
 * The one card surface for NEXO.
 *
 * Cards previously landed on three different radii for the same concept —
 * 8dp in the Discover grid and rail text card, 12dp in `NexoDimens.cardCornerRadius`
 * and the `.pen` card specimen (`Z5a8IZ`), 16dp in `MaterialTheme.shapes.medium`
 * used by `BookCard` — and only some of them drew the hairline border the
 * `.pen` specifies. This component collapses that onto the value the `.pen`
 * specimen and the shipped token agree on: a 12dp radius, a flat
 * `bg-surface` fill, and a 1dp `border-subtle` outline.
 *
 * It never applies `shadowElevation`, per the system's Flat Rule.
 *
 * @param modifier Modifier applied to the surface.
 * @param contentPadding Inner padding. Defaults to 16dp, the `.pen`
 *   `card_padding`. Pass `PaddingValues(0.dp)` for a bleed-to-edge cover card.
 * @param bordered Whether to draw the 1dp hairline. True matches the `.pen`
 *   card; false is for cards whose cover runs to the edge and already reads as
 *   an object.
 * @param content Card body.
 */
@Composable
fun NexoCardSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(NexoDimens.spacingMd),
    bordered: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(NexoDimens.cardCornerRadius)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(NexoColors.surface)
                .then(if (bordered) Modifier.border(1.dp, NexoColors.borderSubtle, shape) else Modifier)
                .padding(contentPadding),
        content = content,
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoCardSurfaceDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoCardSurface(modifier = Modifier.padding(16.dp)) {
            Text(text = "La Odisea", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Homero · 320 páginas",
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoCardSurfaceLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoCardSurface(modifier = Modifier.padding(16.dp)) {
            Text(text = "La Odisea", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Homero · 320 páginas",
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
            )
        }
    }
}

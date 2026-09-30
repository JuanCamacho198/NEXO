package com.nexo.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import kotlin.math.roundToInt

/**
 * Determinate linear progress bar pre-colored to the NEXO theme.
 *
 * Implemented with a plain `Box` + `fillMaxWidth(progress)` instead of
 * Material 3's `LinearProgressIndicator`: the M3 indicator draws a small
 * residual stop at the end of the fill for fractional values (the "1% at
 * the end" artifact), and its internal drawable fights with custom
 * heights/clips. A plain fill has no such artifact and respects any
 * [Modifier] height/shape.
 *
 * The bar renders inside a [Row]; when [showPercentage] is `true` a small
 * `NN%` label is shown at the end of the track.
 *
 * @param progress Current progress in `[0f, 1f]`. Clamped internally.
 * @param modifier Modifier applied to the outer row. Default height 4dp.
 * @param height Thickness of the bar. Default `4.dp`.
 * @param showPercentage Whether to show the percentage label at the end of the track.
 */
@Composable
fun NexoProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    showPercentage: Boolean = true,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(height / 2)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (clamped > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(clamped)
                            .height(height)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (showPercentage) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${(clamped * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoProgressBarDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoProgressBar(
            progress = 0.65f,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoProgressBarLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoProgressBar(
            progress = 0.65f,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

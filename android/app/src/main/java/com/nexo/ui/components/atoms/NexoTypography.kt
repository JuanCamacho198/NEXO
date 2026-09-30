package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Thin, opinionated wrapper over Material 3 `Text`. Centralizes the
 * `maxLines`/`overflow` defaults and surfaces a `Color.Unspecified`
 * default that follows the ambient `LocalContentColor` (i.e. inherits
 * from the surrounding `Surface`/`Button` etc.).
 *
 * @param text The string to display.
 * @param modifier Modifier applied to the underlying `Text`.
 * @param color Text color. `Color.Unspecified` (default) means "inherit
 *   the ambient `LocalContentColor`" rather than forcing `Color.Black`.
 * @param style Text style. Defaults to `LocalTextStyle.current`, which
 *   inside a Material 3 theme is typically the `bodyLarge` typography
 *   slot.
 * @param textAlign Horizontal alignment. `null` (default) lets the
 *   platform default apply (`TextAlign.Start` in LTR locales).
 * @param maxLines Maximum number of lines to render. Default
 *   `Int.MAX_VALUE` (no limit).
 * @param overflow Truncation behavior when the text exceeds [maxLines].
 *   Default `TextOverflow.Clip` (hard cut at the end). Use
 *   `TextOverflow.Ellipsis` to show "…".
 *
 * **Visual**: identical to Material 3 `Text`; this composable exists
 * to enforce consistent overflow/line defaults across the app and to
 * avoid `Color.Unspecified` vs `Color.Black` foot-guns.
 * **Behavior**: none — pure rendering.
 * **Recomposition**: recomposes when `text`, `color`, `style`,
 * `textAlign`, `maxLines`, or `overflow` change.
 */
@Composable
fun NexoTypography(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoTypographyDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoTypography(
                text = "The quick brown fox jumps over the lazy dog",
                style = MaterialTheme.typography.titleMedium,
            )
            NexoTypography(
                text = "Body copy rendered with the default bodyMedium reading style.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NexoTypography(
                text = "Label small — captions and metadata",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTypographyLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoTypography(
                text = "The quick brown fox jumps over the lazy dog",
                style = MaterialTheme.typography.titleMedium,
            )
            NexoTypography(
                text = "Body copy rendered with the default bodyMedium reading style.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NexoTypography(
                text = "Label small — captions and metadata",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

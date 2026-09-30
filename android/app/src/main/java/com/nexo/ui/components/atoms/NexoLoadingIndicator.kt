package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Centered indeterminate loading state: a 48dp `CircularProgressIndicator`
 * with an optional `bodyMedium` label below it.
 *
 * @param modifier Modifier applied to the outer `Column`. Useful for
 *   `fillMaxSize` when the indicator is the only content on screen.
 * @param label Optional text rendered below the spinner. When `null` or
 *   blank, only the spinner is shown. The label is hidden — not just
 *   blanked — in the blank case (no empty `Text` is composed).
 *
 * **Visual**: spinner is `colorScheme.primary`, 48dp. Label uses
 * `colorScheme.onSurfaceVariant` and `bodyMedium`. Both are
 * center-aligned.
 * **Behavior**: pure rendering. The spinner is indeterminate (Material 3
 * default).
 * **Recomposition**: recomposes when `label` changes.
 */
@Composable
fun NexoLoadingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        if (!label.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoLoadingIndicatorDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoLoadingIndicator(
            modifier = Modifier.fillMaxSize(),
            label = "Loading books...",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoLoadingIndicatorLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoLoadingIndicator(
            modifier = Modifier.fillMaxSize(),
            label = "Loading books...",
        )
    }
}

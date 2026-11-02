package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.TranslationPanelState
import com.nexo.ui.icons.NexoIcons

/**
 * Anchored 280dp-wide card showing the machine translation of a selected word
 * or phrase. Read-only sibling of [AnchoredDefinitionInput] — it does NOT own
 * a text field, request focus, or show the keyboard, because a translation is
 * a result, not user input.
 *
 * @param sourceWord The word being translated, rendered as a `titleSmall`
 *   label (the panel is anchored to it).
 * @param state Current [TranslationPanelState] driving the body.
 * @param onDismiss Invoked when the user taps "Close". The caller is expected
 *   to close the surrounding overlay.
 * @param modifier Modifier applied to the outer `Surface`.
 *
 * **Visual**: 280dp-wide `Surface`, 16dp radius, 8dp elevation. Header shows
 *   the source word; the body is either a spinner ([TranslationPanelState.Loading]),
 *   the translated `bodyLarge` text ([TranslationPanelState.Result]), or a
 *   muted message for the no-translation / error states. Footer is a single
 *   right-aligned "Close" text button.
 * **Behavior**: purely presentational; all state is hoisted.
 * **Recomposition**: recomposes when any parameter changes.
 */
@Composable
fun AnchoredTranslationPanel(
    sourceWord: String,
    state: TranslationPanelState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = NexoIcons.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sourceWord,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (state) {
                TranslationPanelState.Loading -> TranslationLoadingBody()
                is TranslationPanelState.Result ->
                    Text(
                        text = state.translated,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                TranslationPanelState.NoTranslation ->
                    MutedBody(stringResource(R.string.translation_no_result))
                is TranslationPanelState.Error -> MutedBody(state.message)
                TranslationPanelState.Idle -> MutedBody(stringResource(R.string.translation_loading))
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.translation_close))
                }
            }
        }
    }
}

@Composable
private fun TranslationLoadingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.translation_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MutedBody(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true)
@Composable
private fun AnchoredTranslationPanelResultPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredTranslationPanel(
            sourceWord = "francés",
            state = TranslationPanelState.Result("French"),
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnchoredTranslationPanelNoResultPreview() {
    NexoTheme(darkTheme = false) {
        AnchoredTranslationPanel(
            sourceWord = "francés",
            state = TranslationPanelState.NoTranslation,
            onDismiss = {},
        )
    }
}

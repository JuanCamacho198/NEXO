package com.nexo.ui.components.molecules

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.translation.NoTranslationReason
import com.nexo.presentation.viewmodel.reader.TranslationPanelState
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The machine translation of the current selection, word or paragraph, in a
 * [ReaderLookupCard] anchored to it.
 *
 * Under the source text sits the [TranslationLanguageBar]: a refusal's copy
 * ("change one of them above") points at a control that is literally above it,
 * and the bar is fed the pair carried by [state], so the bar and the answer
 * always describe one value.
 *
 * The result is set in the reading serif and is selectable, so part of a long
 * translation can be copied; "Copy" takes all of it and confirms in place
 * rather than with a second notification.
 *
 * @param onSourceChosen a source picked in the bar, or `null` for "Automatic (detect)"
 * @param onTargetChosen a destination picked in the bar, or `null` to follow the device
 * @param onSwitchToDefine opens the definition of the same selection
 */
@Composable
fun AnchoredTranslationPanel(
    sourceWord: String,
    state: TranslationPanelState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSourceChosen: (String?) -> Unit = {},
    onTargetChosen: (String?) -> Unit = {},
    onSwitchToDefine: () -> Unit = {},
) {
    ReaderLookupCard(
        mode = LookupMode.TRANSLATE,
        sourceText = sourceWord,
        closeDescription = stringResource(R.string.translation_close),
        onModeChange = { if (it == LookupMode.DEFINE) onSwitchToDefine() },
        onClose = onDismiss,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(10.dp))
        TranslationLanguageBar(
            languages = state.languages,
            onSourceChosen = onSourceChosen,
            onTargetChosen = onTargetChosen,
            disabled = state is TranslationPanelState.Loading,
        )
        Spacer(Modifier.height(12.dp))
        LookupBody {
            when (state) {
                is TranslationPanelState.Loading, TranslationPanelState.Idle ->
                    LookupSkeleton(stringResource(R.string.translation_loading))
                is TranslationPanelState.Result ->
                    SelectionContainer {
                        Text(
                            text = state.translated,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
                            color = ReaderSheetColors.TextPrimary,
                        )
                    }
                is TranslationPanelState.NoTranslation ->
                    LookupMessage(stringResource(noTranslationMessage(state.reason)))
                is TranslationPanelState.Error -> LookupMessage(state.message, isError = true)
            }
        }
        if (state is TranslationPanelState.Result) {
            Spacer(Modifier.height(14.dp))
            CopyTranslationButton(state.translated)
        }
    }
}

@Composable
private fun CopyTranslationButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }
    LookupPillButton(
        label = stringResource(if (copied) R.string.reader_lookup_copied else R.string.reader_lookup_copy_translation),
        icon = if (copied) NexoIcons.Check else NexoIcons.ReaderCopy,
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("translation", text))) }
            copied = true
        },
    )
}

private const val COPIED_FEEDBACK_MS = 1_600L

/**
 * The message for a refusal, keyed on WHY it is a refusal: each names the
 * cause and, where the reader can act, the fix. Exhaustive `when` with no
 * `else`, so a new reason cannot ship without a decision about its copy.
 */
@StringRes
private fun noTranslationMessage(reason: NoTranslationReason): Int =
    when (reason) {
        NoTranslationReason.SAME_LANGUAGE -> R.string.translation_same_language
        NoTranslationReason.UNKNOWN_SOURCE -> R.string.translation_unknown_source
        NoTranslationReason.INVALID_SOURCE -> R.string.translation_invalid_source
        NoTranslationReason.INVALID_TARGET -> R.string.translation_invalid_target
    }

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun AnchoredTranslationPanelResultPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredTranslationPanel(
            sourceWord = "Hoy trabajé mucho en la oficina. El patrón estuvo amable.",
            state = TranslationPanelState.Result("Today I worked a lot at the office. The boss was kind."),
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun AnchoredTranslationPanelNoResultPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredTranslationPanel(
            sourceWord = "francés",
            state = TranslationPanelState.NoTranslation(NoTranslationReason.SAME_LANGUAGE),
            onDismiss = {},
        )
    }
}

package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.translation.ResolvedLanguages
import com.nexo.domain.translation.SourceOrigin
import com.nexo.domain.translation.TranslationLanguageSelection
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * The language pair a translation ran with, as two controls the reader can fix
 * in place: `SOURCE -> DESTINATION`.
 *
 * ## Why it exists
 *
 * A refusal that names the pair but offers no way to change it is a dead end.
 * This bar is what makes "the languages are the same" actionable without
 * leaving the selection — the refusal copy points at the bar, and the bar is
 * one tap away.
 *
 * ## What each side means
 *
 * - A side holding a language the READER chose reads as the bare language name.
 * - A side holding a language the app INFERRED reads as `Auto (X)`, so the
 *   reader can judge the guess instead of trusting it.
 *
 * The distinction is not cosmetic (see [SourceOrigin]): one is a promise the app
 * repeats back, the other a guess it made on the reader's behalf.
 *
 * ## The SOURCE's "Automatic (detect)" entry
 *
 * It is NOT Settings' "Detect automatically". Choosing it here CLEARS this
 * book's choice — "discard what I said about THIS book" — while Settings' entry
 * means "use the default". Reusing the Settings wording would have the reader
 * believe they are restoring a default when they are actually discarding a
 * choice. The label is therefore its own string.
 *
 * ## Presentational only
 *
 * It takes the resolved pair and its origin, and reports a chosen side upward.
 * It owns nothing: no fetch, no settings read, no persistence. The parent (the
 * reader's translation path) owns the write and the re-run, which is what keeps
 * this component free of the storage decision.
 *
 * [disabled] suppresses both controls while a request is in flight, so a reader
 * cannot queue a second change against a pair that has not answered yet.
 *
 * @param languages The pair the panel's body was built from, with the source's
 *   origin. Never a pair the resolver has not seen: the bar and the body must
 *   describe the same value (invariant 6).
 * @param sourceAutoLabel The SOURCE automatic entry's label (this bar's own
 *   wording, distinct from Settings').
 * @param targetAutoLabel The DESTINATION automatic entry's label.
 * @param onSourceChosen A chosen source, or `null` for "Automatic (detect)"
 *   which clears the book's choice.
 * @param onTargetChosen A chosen destination, or `null` to follow the device.
 * @param modifier Modifier applied to the outer row.
 */
@Composable
fun TranslationLanguageBar(
    languages: TranslationLanguageSelection,
    onSourceChosen: (String?) -> Unit,
    onTargetChosen: (String?) -> Unit,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var targetMenuOpen by remember { mutableStateOf(false) }

    val sourceLabel = sourceSideLabel(languages)
    val targetLabel = targetSideLabel(languages)

    // The language list is deliberately the SAME set the Settings screen
    // offers: two pickers with different vocabularies would let the reader
    // choose a language one screen cannot name.
    val languageOptions = LANGUAGE_OPTIONS

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageSide(
            label = sourceLabel,
            contentDescription = stringResource(R.string.translation_bar_source_description),
            enabled = !disabled,
            expanded = sourceMenuOpen,
            onExpandedChange = { sourceMenuOpen = it },
            autoLabel = stringResource(R.string.translation_bar_source_auto),
            // "Automatic (detect)" clears the book's choice: it is a removal,
            // not a stored "automatic" value.
            onSelect = { code ->
                sourceMenuOpen = false
                onSourceChosen(code)
            },
            options = languageOptions,
        )

        Icon(
            imageVector = NexoIcons.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )

        LanguageSide(
            label = targetLabel,
            contentDescription = stringResource(R.string.translation_bar_target_description),
            enabled = !disabled,
            expanded = targetMenuOpen,
            onExpandedChange = { targetMenuOpen = it },
            autoLabel = stringResource(R.string.translation_bar_target_auto),
            onSelect = { code ->
                targetMenuOpen = false
                onTargetChosen(code)
            },
            options = languageOptions,
        )
    }
}

/**
 * One side of the bar: a text button that opens the language menu.
 *
 * It is its own composable rather than two inline copies because the two sides
 * differ only in their data; the menu behaviour (and the "no language chosen
 * yet" placeholder) must be identical, or one side would drift.
 */
@Composable
private fun LanguageSide(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    autoLabel: String,
    onSelect: (String?) -> Unit,
    options: List<LanguageOption>,
) {
    androidx.compose.foundation.layout.Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            // The automatic entry is FIRST, matching Settings' ordering, so the
            // "let the app work it out" escape is the first thing the reader
            // sees rather than the last.
            DropdownMenuItem(
                text = { Text(autoLabel) },
                onClick = { onSelect(null) },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = { onSelect(option.code) },
                )
            }
        }
    }
}

/**
 * What the SOURCE side prints.
 *
 * Three cases, decided by the code AND its origin:
 *
 * - no code resolved -> the generic automatic label. Nothing to name.
 * - a CHOICE ([SourceOrigin.BOOK_PREFERENCE] / [SourceOrigin.PREFERENCE]) ->
 *   the bare language name. It is the reader's own word; labelling it "Auto"
 *   would contradict a choice they made.
 * - inferred ([SourceOrigin.BOOK] / [SourceOrigin.DETECTED] /
 *   [SourceOrigin.DETECTED_FROM_PAGE]) -> "Auto (X)", so the guess is visible
 *   as a guess.
 */
@Composable
private fun sourceSideLabel(languages: TranslationLanguageSelection): String {
    val code = languages.source
    val origin = languages.sourceOrigin
    if (code == null) return stringResource(R.string.translation_bar_source_auto)
    val name = languageName(code)
    if (origin == null || !origin.isInferred) return name
    return stringResource(R.string.translation_bar_auto_with_language, name)
}

/**
 * What the DESTINATION side prints.
 *
 * It has no origin to consult: the destination is always the reader's own —
 * an account choice or the device locale — never per-book (invariant 1), so it
 * stays the generic automatic label when nothing resolved and the bare name
 * otherwise.
 */
@Composable
private fun targetSideLabel(languages: TranslationLanguageSelection): String {
    val code = languages.target ?: return stringResource(R.string.translation_bar_target_auto)
    return languageName(code)
}

/** The display name for a 2-letter code, falling back to the code itself. */
@Composable
private fun languageName(code: String): String =
    when (code.lowercase()) {
        "es" -> stringResource(R.string.settings_translation_spanish)
        "en" -> stringResource(R.string.settings_translation_english)
        "fr" -> stringResource(R.string.settings_translation_french)
        "de" -> stringResource(R.string.settings_translation_german)
        "it" -> stringResource(R.string.settings_translation_italian)
        "pt" -> stringResource(R.string.settings_translation_portuguese)
        // An unknown code is printed as-is rather than hidden: a wrong name is
        // worse than an honest code.
        else -> code
    }

internal data class LanguageOption(
    val code: String,
    val labelRes: Int,
)

/** The shared language set, mirroring `SettingsTranslationScreen`'s options. */
internal val LANGUAGE_OPTIONS =
    listOf(
        LanguageOption("es", R.string.settings_translation_spanish),
        LanguageOption("en", R.string.settings_translation_english),
        LanguageOption("fr", R.string.settings_translation_french),
        LanguageOption("de", R.string.settings_translation_german),
        LanguageOption("it", R.string.settings_translation_italian),
        LanguageOption("pt", R.string.settings_translation_portuguese),
    )

@Preview(showBackground = true)
@Composable
private fun TranslationLanguageBarInferredPreview() {
    NexoTheme(darkTheme = true) {
        Surface {
            TranslationLanguageBar(
                languages =
                    TranslationLanguageSelection(
                        pair = ResolvedLanguages(source = "es", target = "en"),
                        sourceOrigin = SourceOrigin.DETECTED_FROM_PAGE,
                    ),
                onSourceChosen = {},
                onTargetChosen = {},
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslationLanguageBarEmptyPreview() {
    NexoTheme(darkTheme = false) {
        Surface {
            TranslationLanguageBar(
                languages = TranslationLanguageSelection.EMPTY,
                onSourceChosen = {},
                onTargetChosen = {},
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

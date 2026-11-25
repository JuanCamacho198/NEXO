package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.definition.DefinableSelection
import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WIKTIONARY_ATTRIBUTION
import com.nexo.presentation.viewmodel.reader.DefinitionPanelState
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * The body's minimum usable height.
 *
 * A definition list is content the reader ASKED for, so the cap must never cut
 * it to a sliver. This floor is what keeps the body readable when the space the
 * overlay can give it is genuinely tiny — a landscape phone, or a selection
 * pressed against the bottom of an inset box. It is a FLOOR on the cap, not a
 * height the card is drawn at: a one-sense definition stays small.
 */
internal val DEFINITION_BODY_MIN_HEIGHT = 96.dp

/**
 * What a lookup card keeps for itself, outside its scrolling body: the tabs and
 * close row, the headword, the language rows, the action button and the
 * attribution, roughly 220dp together. The floor on the cap means a card that
 * cannot fit the chrome still gives the body its minimum instead of collapsing it.
 */
internal val DEFINITION_CHROME_HEIGHT = 220.dp

/**
 * The chrome's share of the card, as a fraction of the room the overlay has.
 *
 * A BARE `heightIn(max = 300.dp)` — the desktop constant — is wrong on a phone:
 * it would be short enough in landscape (a ~360dp-tall viewport) to swallow the
 * body entirely and leave the Close button pushed off the card, which is the
 * defect being fixed. The cap therefore follows the available space, and the
 * fixed [DEFINITION_CHROME_HEIGHT] is the reserve subtracted from it. Keeping
 * BOTH is deliberate: the fraction bounds the card when the chrome is taller
 * than expected, and the constant keeps the chrome itself on screen.
 */
internal const val DEFINITION_CHROME_HEIGHT_FRACTION = 0.55f

/**
 * The card's height ceiling given the room the overlay has, in px.
 *
 * Relative to the available space rather than a bare constant, so a landscape
 * phone and a portrait one both get a usable card. Pure, so it is unit-testable
 * without a composition. The result is at least [minHeightPx] — the floor — so
 * a very short viewport still gives the body room to be read.
 */
internal fun definitionPanelMaxHeightPx(
    availableHeightPx: Int,
    chromeHeightPx: Int,
    minHeightPx: Int,
    chromeFraction: Float = DEFINITION_CHROME_HEIGHT_FRACTION,
): Int {
    if (availableHeightPx <= 0) return minHeightPx
    val roomForBody = availableHeightPx - chromeHeightPx
    val fractionBound = (availableHeightPx * chromeFraction).toInt()
    return maxOf(minHeightPx, minOf(roomForBody, fractionBound))
}

/**
 * The definition of the current selection in a [ReaderLookupCard] anchored to it.
 *
 * ## The language controls: a LABEL and a SELECTOR, never two selectors
 *
 * - The **word language** is a read-only label, because it is a fact about the
 *   book. When it is UNKNOWN the label SAYS SO (the cascade then assumed `en`)
 *   instead of presenting the guess as a fact.
 * - The **definition language** is a real selector: the reader's own
 *   preference, changed without leaving the card, which re-runs the lookup.
 *
 * ## Every branch renders the attribution
 *
 * Wiktionary text is CC BY-SA 4.0 and the licence requires the attribution to
 * accompany the reused text, so [WIKTIONARY_ATTRIBUTION] is rendered on the
 * result AND on the empty and failure branches, outside the scrolling body so
 * it cannot scroll away.
 *
 * ## A passage is not a dictionary entry
 *
 * The lookup gate refuses anything that is not a word (see
 * [DefinableSelection]); the card then says Define works on words and offers
 * Translate for the same text, instead of "no definition found", which blamed
 * the text. A word simply missing from the dictionary gets the same offer.
 *
 * @param onAddToDictionary the ONLY path from a lookup result to the personal dictionary
 * @param onSwitchToTranslate opens the translation of the same selection
 */
@Composable
fun AnchoredDefinitionPanel(
    sourceWord: String,
    state: DefinitionPanelState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    definitionLanguage: String = "es",
    onDefinitionLanguageChosen: (String) -> Unit = {},
    onAddToDictionary: () -> Unit = {},
    onSwitchToTranslate: () -> Unit = {},
) {
    ReaderLookupCard(
        mode = LookupMode.DEFINE,
        sourceText = sourceWord,
        closeDescription = stringResource(R.string.definition_close),
        onModeChange = { if (it == LookupMode.TRANSLATE) onSwitchToTranslate() },
        onClose = onDismiss,
        modifier = modifier,
    ) {
        // The language rows only mean something for a word: a passage is never
        // looked up, so they would describe a lookup that does not happen.
        if (DefinableSelection.isDefinable(sourceWord)) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = wordLanguageLabel(state.wordLanguage, state.wordLanguageKnown),
                style = MaterialTheme.typography.labelMedium,
                color = ReaderSheetColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            DefinitionLanguageSelector(
                selected = definitionLanguage,
                onChosen = onDefinitionLanguageChosen,
                disabled = state is DefinitionPanelState.Loading,
            )
        }
        Spacer(Modifier.height(14.dp))
        LookupBody {
            when (state) {
                is DefinitionPanelState.Loading, DefinitionPanelState.Idle ->
                    LookupSkeleton(stringResource(R.string.definition_loading))
                is DefinitionPanelState.Result -> DefinitionResultBody(state.result)
                is DefinitionPanelState.Empty -> {
                    val message =
                        if (DefinableSelection.isDefinable(sourceWord)) R.string.definition_no_definition else R.string.reader_lookup_define_words_only
                    LookupMessage(stringResource(message))
                }
                is DefinitionPanelState.Failure ->
                    LookupMessage(stringResource(R.string.definition_service_failed), isError = true, detail = state.message)
            }
        }
        when (state) {
            is DefinitionPanelState.Result -> {
                Spacer(Modifier.height(14.dp))
                LookupPillButton(
                    label = stringResource(R.string.definition_add_to_dictionary),
                    icon = NexoIcons.ReaderSaveWord,
                    onClick = onAddToDictionary,
                    emphasized = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DefinitionPanelState.Empty -> {
                Spacer(Modifier.height(14.dp))
                LookupPillButton(
                    label = stringResource(R.string.reader_lookup_translate_instead),
                    icon = NexoIcons.ReaderTranslate,
                    onClick = onSwitchToTranslate,
                )
            }
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = WIKTIONARY_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = ReaderSheetColors.TextSecondary,
        )
    }
}

/** The read-only word-language statement, saying an unknown language out loud. */
@Composable
private fun wordLanguageLabel(
    wordLanguage: String?,
    wordLanguageKnown: Boolean,
): String =
    if (!wordLanguageKnown) {
        stringResource(R.string.definition_word_language_unknown)
    } else {
        stringResource(R.string.definition_word_language, definitionLanguageName(wordLanguage.orEmpty()))
    }

@Composable
private fun DefinitionLanguageSelector(
    selected: String,
    onChosen: (String) -> Unit,
    disabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.reader_lookup_definitions_in),
            style = MaterialTheme.typography.labelLarge,
            color = ReaderSheetColors.TextSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Box {
            ReaderChip(label = definitionLanguageName(selected), enabled = !disabled, onClick = { expanded = true })
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = ReaderSheetColors.SurfaceRaised,
            ) {
                DEFINITION_LANGUAGE_OPTIONS.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(definitionLanguageName(code), color = ReaderSheetColors.TextPrimary) },
                        onClick = {
                            expanded = false
                            onChosen(code)
                        },
                    )
                }
            }
        }
    }
}

/** Numbered senses, each with its part of speech, after the "translated" mark when there is one. */
@Composable
private fun DefinitionResultBody(result: DefinitionResult) {
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result.source == DefinitionSource.Translated) {
                Text(
                    text = stringResource(R.string.definition_translated_mark),
                    style = MaterialTheme.typography.labelSmall,
                    color = ReaderSheetColors.Accent,
                )
            }
            result.definitions.forEachIndexed { index, item ->
                DefinitionSense(number = index + 1, item = item, numbered = result.definitions.size > 1)
            }
        }
    }
}

@Composable
private fun DefinitionSense(
    number: Int,
    item: DefinitionItem,
    numbered: Boolean,
) {
    Row(verticalAlignment = Alignment.Top) {
        if (numbered) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 1.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(ReaderSheetColors.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = number.toString(), style = MaterialTheme.typography.labelSmall, color = ReaderSheetColors.TextSecondary)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            // Local val: `item.partOfSpeech` is declared in :core:domain, so the
            // cross-module boundary blocks the smart cast after the check.
            val partOfSpeech = item.partOfSpeech
            if (!partOfSpeech.isNullOrBlank()) {
                Text(
                    text = partOfSpeech.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = ReaderSheetColors.Accent,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                color = ReaderSheetColors.TextPrimary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun AnchoredDefinitionPanelResultPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredDefinitionPanel(
            sourceWord = "descontento",
            state =
                DefinitionPanelState.Result(
                    result =
                        DefinitionResult(
                            word = "descontento",
                            definitions =
                                listOf(
                                    DefinitionItem("Disgusto o desagrado.", "sustantivo"),
                                    DefinitionItem("Que no está satisfecho.", "adjetivo"),
                                ),
                            source = DefinitionSource.Native,
                            wordLanguage = "es",
                            readerLanguage = "es",
                        ),
                ),
            onDismiss = {},
            definitionLanguage = "es",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun AnchoredDefinitionPanelPassagePreview() {
    NexoTheme(darkTheme = true) {
        AnchoredDefinitionPanel(
            sourceWord = "Hoy trabajé mucho en la oficina.",
            state = DefinitionPanelState.Empty(word = "Hoy trabajé mucho en la oficina.", wordLanguage = "es", wordLanguageKnown = true),
            onDismiss = {},
            definitionLanguage = "es",
        )
    }
}

@Composable
fun definitionLanguageName(code: String): String =
    when (code.lowercase()) {
        "es" -> stringResource(R.string.definition_language_spanish)
        "en" -> stringResource(R.string.definition_language_english)
        "fr" -> stringResource(R.string.definition_language_french)
        "pt" -> stringResource(R.string.definition_language_portuguese)
        else -> code.ifBlank { stringResource(R.string.definition_language_unknown) }
    }

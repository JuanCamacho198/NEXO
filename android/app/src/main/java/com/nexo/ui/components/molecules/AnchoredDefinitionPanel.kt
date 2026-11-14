package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WIKTIONARY_ATTRIBUTION
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.DefinitionPanelState
import com.nexo.ui.icons.NexoIcons

/**
 * The definition-language options the panel's selector offers. Kept as data so
 * the same list backs the selector and its test, and so the settings sub-page
 * can reuse it.
 */
internal val DEFINITION_LANGUAGE_OPTIONS = listOf("es", "en", "fr", "pt")

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
 * What the card keeps for itself: its own 16dp padding both sides, the header
 * row, the language row, the attribution and the Close row.
 *
 * The chrome is NOT scrolled away — that is the point of the cap — so the space
 * left for the body is the room the overlay has, MINUS this. Measured against
 * the card as it renders: an 18dp header row, a ~40dp language row, the 8dp
 * gaps between sections, the attribution line and a ~48dp Close row come to
 * roughly 220dp. The floor on the cap means a card that cannot fit the chrome
 * still gives the body its minimum instead of collapsing it.
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
 * Anchored 280dp-wide card showing the definition of a selected word.
 * Read-only sibling of [AnchoredTranslationPanel].
 *
 * ## The language controls: a LABEL and a SELECTOR, never two selectors
 *
 * - The **word language** renders as a read-only TEXT label, because it is a
 *   fact about the book, almost never changed, and a selector for it would cost
 *   panel width and reader attention for nothing. When it is UNKNOWN the label
 *   SAYS SO (the cascade then assumed `en`), instead of presenting the guess as
 *   a fact.
 * - The **definition language** is a real SELECTOR: it is the reader's own
 *   preference and they can change it without leaving the panel, which re-runs
 *   the lookup for the same word.
 *
 * The two are different inputs and are NEVER conflated (invariant 1).
 *
 * ## Every branch renders the attribution
 *
 * Wiktionary text is CC BY-SA 4.0 and the licence requires the attribution to
 * accompany the reused text, so [WIKTIONARY_ATTRIBUTION] is rendered on the
 * result AND on the empty and failure branches. The UI does not reconstruct it;
 * it prints the value the domain carries.
 *
 * @param sourceWord The word being defined, rendered as a `titleSmall` label.
 * @param state Current [DefinitionPanelState] driving the body.
 * @param definitionLanguage The reader's definition language (selector value).
 * @param onDefinitionLanguageChosen A language picked in the selector.
 * @param onDismiss Invoked when the reader taps "Close".
 * @param onAddToDictionary The explicit bridge to the personal dictionary: the
 *   ONLY path from the lookup result to `dictionary_words`.
 * @param modifier Modifier applied to the outer `Surface`.
 *
 * ## The body is capped and scrolls; the chrome does not
 *
 * A word with many senses used to grow the card until it consumed the screen,
 * pushing the Close button past the fold where the reader could not reach it: a
 * working button that was practically unusable. Desktop's result panel is capped
 * (`max-height` <= 300px) with `overflow-y-auto` for exactly this; this is the
 * Android equivalent.
 *
 * The cap is on the BODY (`heightIn(max = …)` + `verticalScroll`), never on the
 * card. The header, the language row, the attribution and the Close button sit
 * OUTSIDE the scrolling region, so they are painted whatever the definition
 * does — the body is what scrolls, the chrome does not. The attribution is a
 * licence obligation on every result, so it must not be able to scroll away.
 *
 * The cap is [definitionPanelMaxHeightPx] of the room the overlay has, not a
 * bare constant: see that function. Because it is a MAXIMUM, a one-sense
 * definition renders at its natural height and gets no empty card.
 *
 * **Anchoring**: the card is fixed at 280dp and `AnchoredOverlayBox` measures it
 *   with `onGloballyPositioned` AFTER layout. Adding the language row grows the
 *   card's measured HEIGHT, and because `AnchoredOverlayBox` holds its size in
 *   `mutableIntStateOf` read during composition, the new measurement triggers a
 *   recomposition and the anchor lands on the real card. Nothing here changes
 *   the card's WIDTH, so the horizontal clamp is unaffected — the same solution
 *   [AnchoredTranslationPanel] uses. The cap changes the measured HEIGHT of a
 *   long definition, which is the intent: the anchor is computed from the card
 *   as it will actually be drawn, not from the unbounded one.
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
) {
    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        val density = LocalDensity.current
        val minBodyHeightPx = with(density) { DEFINITION_BODY_MIN_HEIGHT.roundToPx() }
        val chromeHeightPx = with(density) { DEFINITION_CHROME_HEIGHT.roundToPx() }

        // The room the overlay has. `maxHeight` is the inset content box the
        // surface is being placed into — the same box `AnchoredOverlayBox`
        // anchors against — so the cap follows the phone's real orientation
        // instead of a constant chosen for one of them.
        BoxWithConstraints {
            val availableHeightPx = constraints.maxHeight.takeIf { it != Int.MAX_VALUE } ?: 0
            val bodyMaxHeightPx =
                definitionPanelMaxHeightPx(
                    availableHeightPx = availableHeightPx,
                    chromeHeightPx = chromeHeightPx,
                    minHeightPx = minBodyHeightPx,
                )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            ) {
                // The header mark and the "Definir" action share ONE glyph
                // (`NexoIcons.BookSearch`), so the panel is visibly the thing the
                // action opened. The icon is tinted `onSurfaceVariant` rather than
                // `primary`: a saturated accent glyph immediately left of the word
                // took the eye before the word did, and in this build `primary` is
                // a HIGH-CHROMA blue, so the eye landed on the icon as the panel's
                // most colourful element instead of on the term being defined.
                // The mark is orientation, not the message.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NexoIcons.BookSearch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sourceWord,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                DefinitionLanguageRow(
                    wordLanguage = state.wordLanguage,
                    wordLanguageKnown = state.wordLanguageKnown,
                    definitionLanguage = definitionLanguage,
                    onDefinitionLanguageChosen = onDefinitionLanguageChosen,
                    disabled = state is DefinitionPanelState.Loading,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // The ONLY scrolling region: `heightIn` bounds it to a MAXIMUM,
                // so a short definition keeps its natural height and gets no
                // empty card, and `verticalScroll` reaches the extra senses
                // without the card moving.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = with(density) { bodyMaxHeightPx.toDp() })
                            .verticalScroll(rememberScrollState()),
                ) {
                    when (state) {
                        is DefinitionPanelState.Loading -> DefinitionLoadingBody()
                        is DefinitionPanelState.Result -> DefinitionResultBody(state.result)
                        is DefinitionPanelState.Empty ->
                            MutedBody(stringResource(R.string.definition_no_definition))
                        is DefinitionPanelState.Failure -> FailureBody(state.message)
                        DefinitionPanelState.Idle ->
                            MutedBody(stringResource(R.string.definition_loading))
                    }

                    if (state is DefinitionPanelState.Result) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onAddToDictionary,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.definition_add_to_dictionary),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }

                AttributionFooter()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.definition_close))
                    }
                }
            }
        }
    }
}

/**
 * The word-language LABEL and the definition-language SELECTOR, side by side.
 *
 * The label states the word's language (or that it is unknown); the selector is
 * the reader's own preference and re-runs the lookup when changed. Separate
 * controls for separate facts.
 */
@Composable
private fun DefinitionLanguageRow(
    wordLanguage: String?,
    wordLanguageKnown: Boolean,
    definitionLanguage: String,
    onDefinitionLanguageChosen: (String) -> Unit,
    disabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = wordLanguageLabel(wordLanguage, wordLanguageKnown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        DefinitionLanguageSelector(
            selected = definitionLanguage,
            onChosen = onDefinitionLanguageChosen,
            disabled = disabled,
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
        stringResource(
            R.string.definition_word_language,
            definitionLanguageName(wordLanguage.orEmpty()),
        )
    }

/** A single dropdown selector for the definition language. */
@Composable
private fun DefinitionLanguageSelector(
    selected: String,
    onChosen: (String) -> Unit,
    disabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { if (!disabled) expanded = true },
        label = { Text(definitionLanguageName(selected)) },
        trailingIcon = {
            // `onSurfaceVariant`, not the chip's default `localContentColor`.
            // At 18dp this chevron is a wide open "V" sitting immediately
            // right of the language name inside the chip border, and at the
            // content colour it was a third side of that box rather than an
            // affordance — the second of the two shapes the reader read beside
            // the word. Recessing it separates the control from the box.
            Icon(
                imageVector = NexoIcons.ChevronDown,
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        },
        enabled = !disabled,
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DEFINITION_LANGUAGE_OPTIONS.forEach { code ->
            DropdownMenuItem(
                text = { Text(definitionLanguageName(code)) },
                onClick = {
                    expanded = false
                    onChosen(code)
                },
            )
        }
    }
}

/** The definitions with their part of speech, and the "translated" mark. */
@Composable
private fun DefinitionResultBody(result: DefinitionResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.source == DefinitionSource.Translated) {
            Text(
                text = stringResource(R.string.definition_translated_mark),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        result.definitions.forEachIndexed { index, item ->
            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
            DefinitionItemRow(item)
        }
    }
}

@Composable
private fun DefinitionItemRow(item: DefinitionItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!item.partOfSpeech.isNullOrBlank()) {
            Text(
                text = item.partOfSpeech,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DefinitionLoadingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.definition_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The failure body. Deliberately NOT the same copy as the empty branch (ODD
 * invariant 2): this says the service failed, the empty branch says the word
 * was not found. Conflating them tells the reader their word does not exist when
 * the network was down.
 */
@Composable
private fun FailureBody(message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.definition_service_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
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

/**
 * The CC BY-SA attribution. Rendered on EVERY branch — result, empty and
 * failure — because the licence requires it to accompany the reused text.
 */
@Composable
private fun AttributionFooter() {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = WIKTIONARY_ATTRIBUTION,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Human-readable name for a language code, falling back to the code itself. */
@Composable
internal fun definitionLanguageName(code: String): String =
    when (code.lowercase()) {
        "es" -> stringResource(R.string.definition_language_spanish)
        "en" -> stringResource(R.string.definition_language_english)
        "fr" -> stringResource(R.string.definition_language_french)
        "pt" -> stringResource(R.string.definition_language_portuguese)
        else -> code.ifBlank { stringResource(R.string.definition_language_unknown) }
    }

@Preview(showBackground = true)
@Composable
private fun AnchoredDefinitionPanelResultPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredDefinitionPanel(
            sourceWord = "juxtaposition",
            state =
                DefinitionPanelState.Result(
                    result =
                        DefinitionResult(
                            word = "juxtaposition",
                            definitions = listOf(DefinitionItem("yuxtaposición", "sustantivo")),
                            source = DefinitionSource.Translated,
                            wordLanguage = "en",
                            readerLanguage = "es",
                        ),
                ),
            onDismiss = {},
            definitionLanguage = "es",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnchoredDefinitionPanelEmptyPreview() {
    NexoTheme(darkTheme = false) {
        AnchoredDefinitionPanel(
            sourceWord = "zzz",
            state =
                DefinitionPanelState.Empty(
                    word = "zzz",
                    wordLanguage = "en",
                    wordLanguageKnown = false,
                ),
            onDismiss = {},
            definitionLanguage = "es",
        )
    }
}

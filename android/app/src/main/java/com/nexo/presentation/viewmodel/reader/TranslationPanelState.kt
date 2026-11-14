package com.nexo.presentation.viewmodel.reader

import com.nexo.domain.translation.NoTranslationReason
import com.nexo.domain.translation.TranslationLanguageSelection

/**
 * State of the anchored translation panel for the current selection.
 *
 * [Idle] means no translation is in flight and the panel is not shown; the
 * remaining states drive the panel body. Mirrors the shape used by the other
 * interaction surfaces (hoisted state, no side effects).
 *
 * ## Why the language selection is on every state (invariant 6)
 *
 * The panel now renders a language bar above its body, and the bar and the body
 * must describe the SAME pair. Deriving the bar's pair anywhere other than the
 * state the body was built from is how the two drift — the bar would print one
 * thing while the body answered another. Carrying [languages] on each state
 * makes "what the bar shows" and "what the body was built from" the same value
 * by construction, not by convention.
 *
 * While a change is in flight the panel's [Loading] carries the pair the LAST
 * resolved request ran with (the bar keeps showing the truth it has) rather than
 * a half-applied pair, and the body says "Translating…" and nothing else.
 */
sealed interface TranslationPanelState {
    /** The pair this panel was built from, or the empty selection when none resolved. */
    val languages: TranslationLanguageSelection

    data object Idle : TranslationPanelState {
        override val languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY
    }

    /**
     * A request is in flight. [languages] is the pair that request ran with —
     * the last RESOLVED pair, never the one the reader just tapped while the
     * request has not resolved yet.
     */
    data class Loading(
        override val languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
    ) : TranslationPanelState

    data class Result(
        val translated: String,
        override val languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
    ) : TranslationPanelState

    /**
     * No translation was produced, and [reason] says WHICH refusal this is so the
     * panel can name the cause instead of blaming the text.
     *
     * ## Why the reason is required
     *
     * This used to be a fieldless `data object`, so a language-pair COLLISION —
     * where the text is perfectly translatable and the PAIR is the problem —
     * rendered the generic "no translation available for this text". Measured
     * live: selecting "pedirle" in a Spanish book with the app UI in Spanish and
     * the device in English showed exactly that message for a resolvable text.
     *
     * [NoTranslationReason.SAME_LANGUAGE] is the pair collision and must say so;
     * [NoTranslationReason.UNKNOWN_SOURCE] is a detection failure the reader can
     * fix by choosing a source. The generic "missing translation" copy belongs to
     * [NoTranslationReason] values that mean the SERVICE answered with nothing —
     * never to a collision.
     *
     * The refusal KEEPS [languages] so the bar stays visible next to the reason:
     * a refusal that names the pair but offers no way to change it is a dead end.
     */
    data class NoTranslation(
        val reason: NoTranslationReason,
        override val languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
    ) : TranslationPanelState

    data class Error(
        val message: String,
        override val languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
    ) : TranslationPanelState
}

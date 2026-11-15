package com.nexo.presentation.viewmodel.reader

import com.nexo.domain.definition.DefinitionResult

/**
 * State of the anchored definition panel (T8 of `android-define-selection`).
 *
 * [Idle] means no lookup is in flight and the panel is not shown; the remaining
 * states drive the panel body. Mirrors the shape of [TranslationPanelState]
 * (hoisted state, no side effects).
 *
 * ## The three outcomes must not be conflated
 *
 * - [Loading] — a request is in flight.
 * - [Result] — the cascade produced at least one definition.
 * - [Empty] — every source was reachable and none had the word: the honest "no
 *   definition". Distinct type, distinct copy.
 * - [Failure] — the SERVICE failed. This is deliberately NOT [Empty]: telling a
 *   reader their word does not exist when the network was down is the defect
 *   the ODD names first (invariant 2).
 *
 * ## Why the word language is on every state
 *
 * The panel renders a read-only word-language LABEL alongside the
 * definition-language SELECTOR, and the two describe DIFFERENT things: the
 * label is the language the word is written in (a fact about the book, never
 * changed from the panel), the selector is the language the reader wants
 * definitions in. Carrying [wordLanguage] and [wordLanguageKnown] on each
 * state makes the label and the result describe the same lookup by
 * construction, and lets an unknown word language be stated rather than hidden
 * (the cascade then assumes `en`).
 */
sealed interface DefinitionPanelState {
    /** The language the word is in, for the read-only label. `null` until known. */
    val wordLanguage: String?

    /** False when the word's language was unknown and `en` was assumed. */
    val wordLanguageKnown: Boolean

    data object Idle : DefinitionPanelState {
        override val wordLanguage: String? = null
        override val wordLanguageKnown: Boolean = true
    }

    /**
     * A lookup is in flight. [wordLanguage] is carried so the label does not
     * blank out while the request runs.
     */
    data class Loading(
        override val wordLanguage: String? = null,
        override val wordLanguageKnown: Boolean = true,
    ) : DefinitionPanelState

    /** A definition was found. [result] carries the definitions and source mark. */
    data class Result(
        val result: DefinitionResult,
        override val wordLanguage: String? = result.wordLanguage,
        override val wordLanguageKnown: Boolean = result.wordLanguageKnown,
    ) : DefinitionPanelState

    /**
     * Every source was reached and none had the word. The attribution still
     * renders (the licence requires it on every branch).
     */
    data class Empty(
        val word: String,
        override val wordLanguage: String?,
        override val wordLanguageKnown: Boolean,
    ) : DefinitionPanelState

    /**
     * A service failed — no network, a transport error, an unusable response.
     * NOT "the word has no definition" (ODD invariant 2).
     */
    data class Failure(
        val message: String,
        override val wordLanguage: String?,
        override val wordLanguageKnown: Boolean,
    ) : DefinitionPanelState
}

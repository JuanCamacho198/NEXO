package com.nexo.domain.model

/**
 * One row of the definition history, as the settings screen renders it. A flat
 * domain read model so the UI never sees a Room entity or the cached JSON.
 *
 * ## How much of the cached result is exposed
 *
 * The cache stores the whole [com.nexo.domain.definition.DefinitionResult] as
 * JSON: every definition line, its part of speech, the source and the
 * attribution. The history list shows a summary, so this model carries only
 * what a row needs:
 *
 * - [definition] is the FIRST definition line, joined with the rest only when
 *   they exist. A reader scrolling the list wants the gist, not the essay; the
 *   full result is one tap away in the panel, and loading every line for every
 *   row would decode the entire cache on each screen visit.
 * - [partOfSpeech] is the POS of that first line, `null` when the source did not
 *   give one — the UI omits the badge rather than printing an empty one.
 * - The attribution is NOT carried here. It is a constant every result already
 *   re-attaches on decode, and the history list shows no reused text beyond a
 *   truncated gloss; the panel that does show full text carries it.
 *
 * @property word the word as it was selected (case and accents preserved).
 * @property definition the readable meaning; never blank, because a payload with
 *   no definitions is not encodable and a corrupt one is skipped upstream.
 * @property partOfSpeech the POS of [definition], or `null`.
 * @property wordLanguage the language of the word (a fact about the book).
 * @property readerLanguage the language [definition] is written in.
 * @property createdAtEpochMillis when the lookup happened, for the row label.
 */
data class DefinitionHistoryEntry(
    val word: String,
    val definition: String,
    val partOfSpeech: String? = null,
    val wordLanguage: String,
    val readerLanguage: String,
    val createdAtEpochMillis: Long,
)

package com.nexo.domain.definition

import com.nexo.data.remote.wiktionary.WiktionaryFetch

/**
 * A source of raw wikitext: the URL boundary the cascade drives, injected so the
 * ordering can be asserted with fakes and never touches the network in a test.
 *
 * The result tells the cascade three different things and keeps them apart:
 * [WiktionaryFetch.Success] is a page to parse, [WiktionaryFetch.MissingPage] is
 * a reachable wiki that does not have the word, and [WiktionaryFetch.Failure] is
 * a source that did not answer. "Missing" and "failed" are NOT the same, which is
 * what lets the cascade report a true failure honestly (ODD invariant 2).
 */
fun interface WikitextSource {
    suspend fun fetch(
        word: String,
        wikiLanguage: String,
    ): WiktionaryFetch
}

/** A translation of [text] into [targetLanguage], or a failure. */
fun interface GlossTranslator {
    suspend fun translate(
        text: String,
        targetLanguage: String,
    ): String?
}

/**
 * The 3-step definition cascade — the order is the whole design.
 *
 * 1. `{readerLanguage}.wiktionary` for the WORD's own language. When it hits,
 *    the gloss is already in the reader's language and was written by a human
 *    (`casa` for `house`). Marked [DefinitionSource.Native]. Best outcome,
 *    preferred on purpose. SKIPPED when the word's language is unknown, because
 *    the `{{lengua|XX}}` section cannot be chosen.
 * 2. `en.wiktionary`, the same word. The COVERAGE fallback: many words are not
 *    on the reader's-language wiki at all.
 * 3. MyMemory, translating the step-2 English gloss into the reader's language.
 *    Marked [DefinitionSource.Translated], and the UI must say so — this is the
 *    only step that can invent a wrong answer.
 *
 * ## The two outcomes that must not be conflated
 *
 * - Every source REACHED and none had a usable gloss → [DefinitionResult.empty],
 *   the honest "no definition", with `source = null`. A source returning
 *   [WiktionaryFetch.MissingPage] or an empty parse counts as reaching it.
 * - Every source FAILED to answer (network/HTTP/parse) → the result carries a
 *   [DefinitionFailure]. The reader is told the service failed, NOT that their
 *   word does not exist.
 *
 * A transport failure of ONE source never aborts the cascade: that source MISSES
 * and the next one runs. A definition from a later source beats a hard error from
 * an earlier one. Only when EVERY source has failed is a failure reported.
 */
class DefinitionCascade(
    private val nativeSource: WikitextSource,
    private val englishSource: WikitextSource,
    private val translator: GlossTranslator,
    private val englishWikiLanguage: String = ENGLISH_WIKI,
) {
    suspend fun define(request: DefinitionRequest): DefinitionResult {
        val word = request.word.trim()
        val reader = request.readerLanguage
        val wordLanguage = request.wordLanguage
        if (word.isEmpty()) {
            return DefinitionResult(
                word = word,
                definitions = emptyList(),
                source = null,
                wordLanguage = wordLanguage.wordLanguage,
                readerLanguage = reader,
                wordLanguageKnown = wordLanguage.known,
                failure = DefinitionFailure(DefinitionErrorCode.EMPTY_WORD, "empty word"),
            )
        }

        // Step 1 — the reader's-language wiki, native gloss. Skipped when the
        // word's language is unknown.
        var nativeFailed = false
        if (wordLanguage.known) {
            when (val fetch = nativeSource.fetch(word, reader)) {
                is WiktionaryFetch.Success -> {
                    val defs =
                        WiktionaryParser.definitions(fetch.wikitext, wordLanguage.wordLanguage, reader)
                    if (defs.isNotEmpty()) {
                        return nativeResult(word, defs, reader, wordLanguage)
                    }
                }
                WiktionaryFetch.MissingPage -> Unit
                is WiktionaryFetch.Failure -> nativeFailed = true
            }
        }

        // Step 2 — en.wiktionary, English gloss.
        var englishFailed = false
        val englishDefs: List<DefinitionItem> =
            when (val fetch = englishSource.fetch(word, englishWikiLanguage)) {
                is WiktionaryFetch.Success ->
                    WiktionaryParser.definitions(fetch.wikitext, wordLanguage.wordLanguage, englishWikiLanguage)
                WiktionaryFetch.MissingPage -> emptyList()
                is WiktionaryFetch.Failure -> {
                    englishFailed = true
                    emptyList()
                }
            }

        // Step 3 — translate the English gloss into the reader's language. Every
        // sense is attempted; a partial success is still shown.
        if (englishDefs.isNotEmpty()) {
            val items = mutableListOf<DefinitionItem>()
            var translatedAny = false
            for (def in englishDefs) {
                val translated = translator.translate(def.text, reader)
                if (translated != null && translated.isNotBlank()) {
                    translatedAny = true
                    items += DefinitionItem(text = translated, partOfSpeech = def.partOfSpeech)
                }
            }
            if (translatedAny) {
                return DefinitionResult(
                    word = word,
                    definitions = items,
                    source = DefinitionSource.Translated,
                    wordLanguage = wordLanguage.wordLanguage,
                    readerLanguage = reader,
                    wordLanguageKnown = wordLanguage.known,
                )
            }
        }

        // Every source was reached and none had a usable gloss: the honest empty
        // result, not an error.
        if (!nativeFailed && !englishFailed) {
            return DefinitionResult.empty(
                word = word,
                readerLanguage = reader,
                wordLanguage = wordLanguage.wordLanguage,
                wordLanguageKnown = wordLanguage.known,
            )
        }

        // At least one source failed. If NOTHING was reachable, that is a service
        // failure and must not be presented as "the word has no definition".
        return DefinitionResult(
            word = word,
            definitions = emptyList(),
            source = null,
            wordLanguage = wordLanguage.wordLanguage,
            readerLanguage = reader,
            wordLanguageKnown = wordLanguage.known,
            failure =
                DefinitionFailure(
                    code = DefinitionErrorCode.NETWORK,
                    message = "no source could be reached",
                ),
        )
    }

    private fun nativeResult(
        word: String,
        defs: List<DefinitionItem>,
        reader: String,
        wordLanguage: WordLanguage,
    ): DefinitionResult =
        DefinitionResult(
            word = word,
            definitions = defs,
            source = DefinitionSource.Native,
            wordLanguage = wordLanguage.wordLanguage,
            readerLanguage = reader,
            wordLanguageKnown = wordLanguage.known,
        )

    private companion object {
        const val ENGLISH_WIKI = "en"
    }
}

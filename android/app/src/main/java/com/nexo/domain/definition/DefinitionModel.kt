package com.nexo.domain.definition

/**
 * Attribution required by Wiktionary's licence (CC BY-SA 4.0).
 *
 * It is carried on EVERY [DefinitionResult] — including the empty one — because
 * the licence requires it to accompany the reused text, and the UI cannot be
 * trusted to reconstruct it from a flag. A result type without this field would
 * make the obligation forgettable; here a caller cannot render definitions
 * without holding it.
 */
const val WIKTIONARY_ATTRIBUTION: String =
    "Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)"

/**
 * Wikimedia requires an identifiable User-Agent with a real contact. A generic
 * or missing agent is blocked with a 403, so this string is load-bearing, not
 * cosmetic (mirrors the catalog UA policy).
 */
const val NEXO_WIKTIONARY_USER_AGENT: String =
    "Nexo/Android (https://github.com/nexo-reader/nexo; contact: nexo-reader@proton.me)"

/**
 * Where a definition came from, which decides how much the reader may trust it.
 *
 * [Native] is a gloss a human wrote in the reader's language. [Translated] is a
 * machine translation of an English gloss: right most of the time, wrong in
 * ways the reader cannot see, so the UI must say so.
 */
enum class DefinitionSource {
    Native,
    Translated,
}

/** One definition line as the reader shows it. */
data class DefinitionItem(
    val text: String,
    val partOfSpeech: String? = null,
)

/**
 * The whole result of a definition lookup.
 *
 * [definitions] is empty ONLY when every source missed — the honest "no
 * definition" the reader renders as such. [source] is `null` in that case;
 * otherwise it names step 1 ([DefinitionSource.Native]) or step 3
 * ([DefinitionSource.Translated]).
 *
 * [failure] is the OTHER truth: the lookup could not complete because a service
 * failed (no network, a transport error, an unusable response). An empty
 * [definitions] with a non-null [failure] is NOT "the word has no definition" —
 * conflating the two would tell the reader their word does not exist when the
 * network was simply down.
 *
 * @property wordLanguage the language of the WORD (a fact about the book), kept
 *   so the panel can state it and say when it was unknown. Never the language
 *   the definitions are written in — see [readerLanguage].
 * @property readerLanguage the language the definitions are written in (the
 *   reader's own preference).
 * @property wordLanguageKnown false when the word's language was unknown and
 *   the cascade assumed `en`; the panel says so instead of hiding it.
 * @property attribution always [WIKTIONARY_ATTRIBUTION], on every branch.
 */
data class DefinitionResult(
    val word: String,
    val definitions: List<DefinitionItem>,
    val source: DefinitionSource?,
    val attribution: String = WIKTIONARY_ATTRIBUTION,
    val wordLanguage: String,
    val readerLanguage: String,
    val wordLanguageKnown: Boolean = true,
    val failure: DefinitionFailure? = null,
) {
    /** True when every source missed honestly — not the same as [failure]. */
    val isEmpty: Boolean get() = definitions.isEmpty() && failure == null

    companion object {
        /**
         * The honest "no definition": every source was reachable and none had
         * the word. Never cached (see the ODD) — it is a statement about the
         * network that day, not about the word.
         */
        fun empty(
            word: String,
            readerLanguage: String,
            wordLanguage: String,
            wordLanguageKnown: Boolean,
        ): DefinitionResult =
            DefinitionResult(
                word = word,
                definitions = emptyList(),
                source = null,
                wordLanguage = wordLanguage,
                readerLanguage = readerLanguage,
                wordLanguageKnown = wordLanguageKnown,
            )
    }
}

/**
 * Why a lookup could not complete. Distinct from "no definition": this is the
 * service failing, and the reader must see the difference (ODD invariant 2).
 */
data class DefinitionFailure(
    val code: DefinitionErrorCode,
    val message: String,
)

/** Terminal failure codes for a lookup. Never thrown — always returned. */
enum class DefinitionErrorCode {
    /** No network, timeout, or the transport threw on every source. */
    NETWORK,

    /** A source answered with a non-success HTTP status. */
    HTTP_ERROR,

    /** A source answered with a body that could not be decoded. */
    PARSE_ERROR,

    /** The word was empty, so there was nothing to look up. */
    EMPTY_WORD,
}

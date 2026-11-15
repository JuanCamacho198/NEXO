package com.nexo.domain.definition

/**
 * The language of the selected WORD — a fact about the BOOK, never a preference.
 *
 * ## Why this is its own type
 *
 * A definition has TWO languages and they are different inputs:
 *
 * - the language the WORD is in — this type. It picks the `{{lengua|XX}}`
 *   section on a Wiktionary page that carries twenty languages.
 * - the language the DEFINITION is written in ([DefinitionRequest.readerLanguage])
 *   — what the reader wants to read.
 *
 * Collapsing them is the defect the ODD calls out first: a lookup with the
 * reader's language used as the word's would read the wrong section and answer
 * about the wrong word. The type keeps them separable.
 *
 * ## Where it comes from, and the fallback
 *
 * It comes from [com.nexo.domain.model.Book.language] through the source
 * cascade that the language-resolution work builds. There is NO detection step —
 * off-device detection of a single word is unreliable and would add a round-trip
 * before the first lookup.
 *
 * When the language is UNKNOWN the cascade cannot choose a `{{lengua|XX}}` block
 * safely, so it SKIPS step 1 (the reader's-language wiki) and assumes `en` for
 * steps 2 and 3. [wordLanguage] still reads `en` in that case because that is
 * what the cascade actually used; [known] is false so the panel can say the
 * language was unknown instead of presenting the guess as a fact.
 *
 * This mirrors desktop's `SourceLanguage` exactly: same source, same fallback,
 * same honesty.
 */
data class WordLanguage(
    val wordLanguage: String,
    val known: Boolean,
) {
    companion object {
        /** The language the cascade assumes when the book does not declare one. */
        const val ASSUMED = "en"

        /** A language the book states. */
        fun known(wordLanguage: String): WordLanguage = WordLanguage(wordLanguage = wordLanguage.trim(), known = true)

        /** No usable language: step 1 is skipped and `en` is assumed. */
        fun unknown(): WordLanguage = WordLanguage(wordLanguage = ASSUMED, known = false)

        /**
         * Normalise an optional book-supplied code. Blank, null and unusable input
         * all resolve to [unknown] — the same posture
         * [com.nexo.domain.translation.TranslationPolicy.normalizeLanguage] takes,
         * so an empty metadata field never becomes a fake language.
         */
        fun fromOptional(code: String?): WordLanguage {
            val trimmed = code?.trim().orEmpty()
            if (trimmed.isEmpty()) return unknown()
            val normalized =
                com.nexo.domain.translation.TranslationPolicy
                    .normalizeLanguage(trimmed, fallback = "")
            return if (normalized.isEmpty()) unknown() else known(normalized)
        }
    }
}

/**
 * One definition lookup request, with the two languages named separately so a
 * caller cannot pass one where the other belongs.
 *
 * @property word the selected text.
 * @property readerLanguage the language the definitions must be written in —
 *   the reader's own preference, resolved from
 *   [com.nexo.data.session.DefinitionPreferences] (stored value → app UI locale
 *   → `en`). It decides which wiki hosts step 1, which wiki is queried in step 2,
 *   and the MyMemory target in step 3.
 * @property wordLanguage the language of the word, from the book. See
 *   [WordLanguage].
 */
data class DefinitionRequest(
    val word: String,
    val readerLanguage: String,
    val wordLanguage: WordLanguage,
)

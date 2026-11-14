package com.nexo.domain.definition

/**
 * The C+A limit as a pure, total predicate (ODD T6).
 *
 * A definition lookup costs three network requests when it misses, and the
 * cascade will answer "no definition" for anything it is handed. The menu
 * therefore needs a cheap local test for "is this selection even a candidate?"
 * so a paragraph does not spend three requests to be told it is a paragraph.
 *
 * ## The rule
 *
 * A selection is definable when it:
 *
 * 1. **has a letter** — `123`, `…` and `!!!` are refused for lack of one. This
 *    is deliberately a *letter* test, not an alphanumeric one: `42` is not a
 *    word, and `O(n)` is not either.
 * 2. **has no strong sentence punctuation *inside* it** — a `.`, `,`, `;`,
 *    `:`, `!`, `?`, `…`, a quote, bracket or newline *between* the letters
 *    means the reader selected prose, not a headword. A sentence is refused on
 *    its punctuation even when it is short. Weak word-internal marks are
 *    allowed: hyphens (`well-known`), apostrophes (`l'école`, `don't`) and a
 *    leading slash (a Wiktionary alternative form like `gray/grey`).
 * 3. **is at most [MAX_WORDS] whitespace-separated words** — a word (`house`),
 *    a phrasal verb (`give up`) and an idiom (`a piece of cake`) are admitted;
 *    a paragraph is not.
 *
 * ## Surrounding punctuation is not the reader's selection
 *
 * Rule 2 applies to the punctuation the reader MEANT to include, which is the
 * punctuation bracketed by letters. Marks at the two ends are an artifact of
 * where the drag landed, not a statement about the selection: the book renders
 * `El asilo "de" ancianos`, the drag covers the quote glyphs, and the word the
 * reader wants is `de`. The same happens on a full stop (`efectismos.`), a
 * comma, a closing bracket and an ellipsis — the quote is one instance of a
 * general shape, so the fix is general and [trimSurroundingPunctuation] is what
 * makes it.
 *
 * What keeps prose refused is the kind of mark that was trimmed. An edge mark
 * is one of two things, and they are not the same:
 *
 * - a **delimiter** — a quote or a bracket, which *surrounds* whatever sits
 *   between the pair without being part of it. Trimming it leaves the phrase
 *   intact and cannot turn prose into a headword: `"de"` is `de` and
 *   `"a piece of cake"` is `a piece of cake`.
 * - a **terminator** — `.`, `!`, `?`, `…`, which end a SENTENCE. Trimming it is
 *   only safe when there is no sentence left to end: `efectismos.` is the word
 *   `efectismos` plus a stray full stop (one word), but `She went to the
 *   market.` is a sentence whose full stop is load-bearing. A terminator is
 *   therefore trimmed only when the remainder is a SINGLE word.
 *
 * A selection that is *only* punctuation must NOT be trimmed into life: it has
 * no letter, so rule 1 still refuses it.
 *
 * The trimmed word — never the raw selection — is what the gate judges and what
 * the lookup sends, so the two cannot disagree about what the word is.
 *
 * ## Totality
 *
 * The function is TOTAL: every input — empty, blank, a lone surrogate, a
 * 10 000-word wall of text — returns a `Boolean` and none of them throws. That
 * is the point of the limit living in the domain as its own function: a
 * `require`/`check` here would turn a bad selection into a crash in the
 * reader's selection path.
 *
 * The same predicate gates the action's ENABLED state and the lookup itself, so
 * a disabled button and a refused request cannot disagree.
 */
object DefinableSelection {
    /** The C+A word ceiling: a word, a phrasal verb or a short idiom. */
    const val MAX_WORDS: Int = 5

    /**
     * Strong sentence punctuation: any of these means the reader selected a
     * PASSAGE. The apostrophes here are the typewriter AND the two typographic
     * forms, so a smart-quoted book is refused the same as a plain one.
     *
     * `/` is deliberately absent: it joins alternative forms (`gray/grey`), it
     * does not end a sentence.
     */
    private const val STRONG_PUNCTUATION = ".,;:!?…\"'“”‘’«»()[]{}<>—–\n\r\t"

    /** `true` when [selection] is a candidate for a definition lookup. */
    fun isDefinable(selection: String?): Boolean {
        val text = trimSurroundingPunctuation(selection)
        if (text.isEmpty()) return false
        if (!text.any { it.isLetter() }) return false
        if (text.any { it in STRONG_PUNCTUATION }) return false
        return text.split(WHITESPACE).size <= MAX_WORDS
    }

    /**
     * Strips the punctuation that brackets a selection WITHOUT being part of
     * the word: the marks the drag happened to cover at either end.
     *
     * The rule this restores is the one the gate was written for — a `.`, a
     * `,`, a `"` or a `)` *inside* a selection means the reader took a passage.
     * A mark at the two ends is not that: `"de"`, `de.`, `(de)` and `de…` are
     * all the word `de` as the drag landed on it, and they are one shape, not
     * four, so this is a general trim rather than a quote special case.
     *
     * ## Delimiters always go; terminators go only from a single word
     *
     * A **delimiter** (a quote or a bracket) surrounds what sits between the
     * pair and is never part of it, so removing it cannot turn prose into a
     * headword: `"a piece of cake"` trims to the idiom. A **terminator**
     * (`.`, `!`, `?`, `…`) ends a *sentence*, and removing it from
     * `She went to the market.` would leave prose the gate would then admit —
     * so a terminator is stripped only when what remains is a SINGLE word
     * (`efectismos.` → `efectismos`). This is what keeps "prose is still
     * refused" true while `"de"` is defined.
     *
     * A selection that is only punctuation trims to empty and is then refused by
     * [isDefinable]'s letter rule, exactly as before. Weak in-word marks
     * (hyphens, apostrophes, a slash) survive unless they sit at an end, where
     * they cannot be word-internal anyway.
     *
     * Returns the selection unchanged when it carries no leading/trailing
     * marks: a normal word must reach the gate and the lookup byte-identical.
     */
    fun trimSurroundingPunctuation(selection: String?): String {
        val withoutDelimiters = selection?.trim().orEmpty().trim { it in DELIMITERS }
        // A terminator is safe to strip only when a single word remains under it.
        return if (withoutDelimiters.split(WHITESPACE).size <= 1) {
            withoutDelimiters.trim { it in TERMINATORS }
        } else {
            withoutDelimiters
        }
    }

    /**
     * Marks that SURROUND a value without being part of it: quotes and brackets.
     * Removing one of these cannot admit prose — it only removes the fence the
     * drag happened to include.
     */
    private const val DELIMITERS = "\"'“”‘’«»()[]{}<>"

    /**
     * Marks that END a sentence. Stripping one from a multi-word selection would
     * turn prose into a headword, so these are trimmed only from a single word.
     */
    private const val TERMINATORS = ".,;:!?…—–"

    private val WHITESPACE = Regex("\\s+")
}

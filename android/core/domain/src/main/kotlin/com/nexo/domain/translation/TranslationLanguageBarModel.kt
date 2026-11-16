package com.nexo.domain.translation

/**
 * Where a resolved SOURCE came from — what lets the bar label a side honestly.
 *
 * The distinction is not cosmetic. A language the READER chose is theirs and
 * reads as the bare language name; a language the app INFERRED reads as
 * "Auto (X)" so the reader can judge it. Mirror of desktop's `SourceOrigin`,
 * reduced to the origins Android's cascade can produce.
 *
 * The order of the constants follows the cascade, so an ordinal comparison
 * reads as "earlier step wins" — but nothing compares ordinals; each value is
 * matched explicitly.
 */
enum class SourceOrigin {
    /** The reader's choice for THIS book (the bar's per-book value). */
    BOOK_PREFERENCE,

    /** The reader's account-wide choice (`TranslationSettings.sourceLanguage`). */
    PREFERENCE,

    /** The book's own metadata (`Book.language`). */
    BOOK,

    /** Detected over the selected text. */
    DETECTED,

    /** Detected over the page, after the selection yielded nothing. */
    DETECTED_FROM_PAGE,
    ;

    /** Whether this origin is a guess the app made rather than the reader's word. */
    val isInferred: Boolean
        get() = this == BOOK || this == DETECTED || this == DETECTED_FROM_PAGE
}

/**
 * What the language bar RENDERS and what it needs to send back.
 *
 * ## Why the pair and its origin travel together (invariant 6)
 *
 * The bar shows `SOURCE -> DESTINATION`. The SOURCE side needs two facts, not
 * one: the CODE (to print a name) and the ORIGIN (to decide whether that name is
 * a promise or a guess). Deriving the origin separately at render time is how
 * the bar and the body's pair drift apart; carrying both in one resolved value
 * is what keeps them the same truth.
 *
 * [pair] is the pair the RESOLVER last ran with, and it is `null` exactly when
 * nothing resolved. The bar still renders a source side in that case, labelled
 * with the generic automatic text — there is simply no code to name.
 *
 * The destination has no origin: it is always the reader's own (account choice
 * or device locale), never per-book (invariant 1).
 */
data class TranslationLanguageSelection(
    /** The pair the resolution produced, or `null` when nothing resolved. */
    val pair: ResolvedLanguages?,
    /** Why the SOURCE side holds [pair]'s source, or `null` when none resolved. */
    val sourceOrigin: SourceOrigin?,
) {
    init {
        require((pair == null) == (sourceOrigin == null)) {
            "A selection must carry either a pair with an origin, or neither"
        }
    }

    val source: String? get() = pair?.source
    val target: String? get() = pair?.target

    companion object {
        /** Nothing resolved: the bar shows the automatic label on both sides. */
        val EMPTY = TranslationLanguageSelection(pair = null, sourceOrigin = null)
    }
}

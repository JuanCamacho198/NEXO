package com.nexo.domain.translation

/**
 * Why a translation should not be attempted, as a type the UI can render its own
 * message for.
 *
 * Modelled on the desktop equivalent (`NoTranslationReason`), which is a union of
 * the same four values. Android previously had only an untyped `null` from
 * [TranslationPolicy.resolveLanguages], so a language-pair COLLISION and a
 * missing translation were indistinguishable and rendered the same message —
 * which blamed the text for a problem that was the pair's.
 *
 * The union is deliberately WIDER than the resolver produces today. The
 * reachable reasons are [SAME_LANGUAGE] (a collision) and [UNKNOWN_SOURCE]
 * (neither the selection nor the page could name the source); the other two
 * exist so an unusable configured side can be expressed later WITHOUT changing
 * the shape again. Adding a value here must never change what today's
 * resolution returns.
 */
enum class NoTranslationReason {
    /** The resolved source equals the resolved target: the pair is the problem. */
    SAME_LANGUAGE,

    /**
     * Nothing could name the source — a detection failure.
     *
     * Reachable since Group 2: [TranslationPolicy.guessSourceLanguage] returns
     * `null` when a text carries no Spanish or English marker, so a selection
     * with nothing to detect falls through to the page, and a page that detects
     * nothing either ends here. The reader fixes it by choosing a source in the
     * bar.
     */
    UNKNOWN_SOURCE,

    /** A configured source language is present but unusable (`""`, `"1"`). */
    INVALID_SOURCE,

    /** A configured target language is present but unusable. */
    INVALID_TARGET,
}

/**
 * The outcome of resolving a language pair: either the pair to translate with, or
 * the reason there is none.
 *
 * [reason] is required to be non-null exactly when [pair] is null, so a caller
 * can render a message for every refusal — which is the whole point. Kept as a
 * small value type rather than a sealed hierarchy because the pair is the thing
 * every existing caller reads, and `pair` keeps that call site a one-liner.
 */
data class TranslationResolution(
    val pair: ResolvedLanguages?,
    val reason: NoTranslationReason?,
    /**
     * Where [pair]'s SOURCE came from, required non-null exactly when [pair] is.
     *
     * The bar reads this to decide whether the source it prints is the reader's
     * own word (bare language name) or a guess the app made ("Auto (X)"). It is
     * carried ON the resolution rather than re-derived at render time so the bar
     * and the body cannot describe different pairs (invariant 6).
     */
    val sourceOrigin: SourceOrigin? = null,
) {
    init {
        require((pair == null) == (reason != null)) {
            "A resolution must carry either a pair or a reason, never both and never neither"
        }
        require((pair == null) == (sourceOrigin == null)) {
            "A resolution must carry a source origin exactly when it carries a pair"
        }
    }
}

package com.nexo.domain.translation

/**
 * Terminal outcome of a translation request, mapped from the data layer so the
 * UI never sees transport types.
 */
sealed interface TranslationOutcome {
    data class Translated(
        val text: String,
        val fromCache: Boolean,
    ) : TranslationOutcome

    /** The upstream answered but echoed the source (no real translation). */
    data object NoTranslation : TranslationOutcome

    /** No network / timeout / transport failure. */
    data object Offline : TranslationOutcome

    /** Upstream error (HTTP, status, parse). */
    data class Error(
        val message: String,
    ) : TranslationOutcome
}

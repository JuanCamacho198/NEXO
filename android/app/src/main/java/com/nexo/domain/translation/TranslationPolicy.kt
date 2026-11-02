package com.nexo.domain.translation

/**
 * Pure translation policy — no I/O, no Android, fully unit-testable.
 *
 * The MyMemory API is deliberately cheap to call and requires no key, but its
 * `match` confidence score is NOT trustworthy: a real query returned
 * `francés` → `francés` with `match: 0.99`. The score describes the corpus
 * match of the *segmentation*, not whether the text changed. The only reliable
 * signal that a translation happened is that the output differs from the input
 * ([isRealTranslation]); everything else must be treated as "no translation".
 */
object TranslationPolicy {
    /** Language assumed for the app UI when the platform gives us nothing usable. */
    const val DEFAULT_LANGUAGE = "en"

    /**
     * Whether [translated] is a genuinely different string from [original].
     *
     * Returns false when the API echoed the source back (the `francés` defect),
     * comparing trimmed and case-insensitively so `"Francés "` still counts as
     * an echo. An empty or blank output is never a real translation.
     */
    fun isRealTranslation(
        original: String,
        translated: String,
    ): Boolean {
        val out = translated.trim()
        if (out.isEmpty()) return false
        return !out.equals(original.trim(), ignoreCase = true)
    }

    /**
     * Normalises a raw language tag to a MyMemory-compatible 2-letter code.
     * Accepts `es`, `ES`, `es-ES`, `es_ES`, `es-419` → `es`. Unknown or blank
     * input falls back to [fallback].
     */
    fun normalizeLanguage(
        tag: String?,
        fallback: String = DEFAULT_LANGUAGE,
    ): String {
        val cleaned = tag?.trim()?.lowercase().orEmpty()
        if (cleaned.isEmpty()) return fallback
        val base = cleaned.substringBefore('-').substringBefore('_')
        return if (BASE_LANGUAGE_REGEX.matches(base)) base else fallback
    }

    /**
     * Builds the MyMemory `langpair` value. MyMemory expects lowercase
     * 2-letter ISO 639-1 codes joined by a pipe, e.g. `es|en`.
     */
    fun langPair(
        source: String,
        target: String,
    ): String = "${normalizeLanguage(source)}|${normalizeLanguage(target)}"

    /**
     * Decides whether a cached entry may be served instead of hitting the
     * network. A cache hit requires the exact same [langPair] AND that the
     * requested [text] matches the cached [original] — a translation is only
     * valid for the text and pair it was produced from.
     *
     * Returns the cached (non-blank) translation when usable, else null.
     */
    fun cachedTranslationOrNull(
        cached: CachedTranslation?,
        text: String,
        langPair: String,
    ): String? {
        if (cached == null) return null
        if (cached.langPair != langPair) return null
        if (!cached.original.trim().equals(text.trim(), ignoreCase = true)) return null
        val translated = cached.translated.trim()
        if (translated.isEmpty()) return null
        return translated
    }

    private val BASE_LANGUAGE_REGEX = Regex("[a-z]{2}")

    /**
     * Cheap heuristic language detector used only when [Book.language] is
     * unknown. Deliberately tiny and total: it decides between Spanish and
     * English (the two languages the app ships) and defaults to English.
     *
     * Signals typical of Spanish and absent from English: `ñ`, and the
     * high-frequency function words `el/la/los/las/de/que/y/una/por`. A
     * Spanish word like `francés` matches on the accent-bearing suffix; an
     * English word matches neither and falls to English.
     */
    fun guessSourceLanguage(
        text: String,
        fallback: String = DEFAULT_LANGUAGE,
    ): String {
        val lower = text.lowercase()
        if (lower.isEmpty()) return fallback
        if (SPANISH_MARKERS.any { lower.contains(it) }) return "es"
        if (ENGLISH_MARKERS.any { lower.contains(it) }) return "en"
        // A word bearing a Spanish-only accent (á/é/í/ó/ú/ñ) leans Spanish.
        if (SPANISH_ACCENT_REGEX.containsMatchIn(lower)) return "es"
        return fallback
    }

    private val SPANISH_MARKERS =
        listOf("ñ", " de ", " el ", " la ", " los ", " las ", " que ", " y ", " por ", " una ", " con ")
    private val ENGLISH_MARKERS =
        listOf(" the ", " and ", " of ", " to ", " is ", " in ", " that ", " for ", " with ")
    private val SPANISH_ACCENT_REGEX = Regex("[áéíóúüñ]")
}

/** Minimal read model for a cached translation — keeps [TranslationPolicy] free of Room. */
data class CachedTranslation(
    val original: String,
    val langPair: String,
    val translated: String,
)

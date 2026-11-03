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

    /**
     * Resolves the MyMemory `langpair` for a reader selection, or `null` when
     * there is nothing sensible to ask for.
     *
     * ## Why this exists (T1 of `translation-preferences`)
     *
     * The previous inline resolution in `ReaderViewModel.onTranslateSelection`
     * took the target from the system locale and the source from the book, with
     * no identity check at this layer. A book in English on an English phone
     * therefore produced `en|en`: MyMemory echoed the source, [isRealTranslation]
     * (server-side) reported no change, and the panel showed "No translation
     * available" to a user the feature should have served.
     *
     * ## Resolution order
     *
     * - **Target**: [TranslationSettings.targetLanguage] when set, else
     *   [systemLanguage].
     * - **Source**: [TranslationSettings.sourceLanguage] when set, else
     *   [bookLanguage] when usable, else [guessSourceLanguage] of [selectedText]
     *   with the target as fallback.
     *
     * Both are normalised to a 2-letter code. When the resolved codes are equal
     * the call returns `null` — the caller must inform rather than ask, because
     * a self-pair is always an echo.
     */
    fun resolveLangPair(
        settings: TranslationSettings,
        bookLanguage: String?,
        systemLanguage: String,
        selectedText: String,
    ): String? {
        val resolved = resolveLanguages(settings, bookLanguage, systemLanguage, selectedText)
        return resolved?.let { langPair(it.source, it.target) }
    }

    /**
     * Same resolution as [resolveLangPair] but keeping source and target apart,
     * because [com.nexo.domain.repository.TranslationRepository.translate] takes
     * them as two arguments. The repository re-joins them into the `langpair`,
     * so the pair string a caller reads back is identical.
     */
    fun resolveLanguages(
        settings: TranslationSettings,
        bookLanguage: String?,
        systemLanguage: String,
        selectedText: String,
    ): ResolvedLanguages? {
        val target = normalizeLanguage(settings.targetLanguage ?: systemLanguage)
        val source =
            normalizeLanguage(
                tag = settings.sourceLanguage,
                fallback = "",
            ).takeIf { it.isNotEmpty() }
                ?: normalizeLanguage(
                    tag = bookLanguage,
                    fallback = "",
                ).takeIf { it.isNotEmpty() }
                ?: guessSourceLanguage(selectedText, fallback = target)

        if (source == target) return null
        return ResolvedLanguages(source = source, target = target)
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

/** A resolved, non-identical source/target pair produced by [TranslationPolicy.resolveLanguages]. */
data class ResolvedLanguages(
    val source: String,
    val target: String,
)

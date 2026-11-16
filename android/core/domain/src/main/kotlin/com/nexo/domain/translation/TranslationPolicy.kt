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
     *
     * Also returns false when the output is HTML markup rather than text. The
     * `«` → `&laquo;` defect was measured against the live API: an entity is
     * not a translation, and rendering it in the panel shows `&laquo;` to the
     * reader. The check is on the entity PATTERN, not on `&` itself, so `AT&T`
     * and `R&D` are unaffected.
     */
    fun isRealTranslation(
        original: String,
        translated: String,
    ): Boolean {
        val out = translated.trim()
        if (out.isEmpty()) return false
        if (HTML_ENTITY_REGEX.containsMatchIn(out)) return false
        return !out.equals(original.trim(), ignoreCase = true)
    }

    /**
     * Whether [text] deserves a translation request at all.
     *
     * ## Why this exists (measured against the live MyMemory API)
     *
     * Eight nonsensical selections were sent. Five were pure waste and two
     * produced actively bad output: `.` came back as `bility` (an invented
     * word) and `«` as `&laquo;` (markup). [isRealTranslation] cannot catch
     * either, because it only asks whether the output *changed* — and both
     * did. The guard has to run BEFORE the request, on the input.
     *
     * ## Rules
     *
     * - Blank, or shorter than [MIN_TRANSLATABLE_LENGTH] after trim → false.
     * - Contains no letter at all (`.`, `—`, `123`, `¿?`, `«`) → false.
     * - Looks like a URL or an email address → false. A selection of
     *   `https://…` is not book prose, and it is cheap to recognise.
     *
     * ## The `y` trade-off (documented decision)
     *
     * `y` → `and` is a CORRECT translation, and the length rule rejects it. The
     * rule was kept: a one-character selection is dominated by accidental
     * drag-handle selections, and an allowlist of one-letter words would need
     * to be maintained per source language. The cost of the miss is one
     * untranslated connector; the cost of the alternative is a growing
     * exception list for a case a reader will almost never intend.
     * `TranslationPolicyTest` pins this explicitly rather than leaving it
     * implicit.
     */
    fun isWorthTranslating(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TRANSLATABLE_LENGTH) return false
        if (!trimmed.any { it.isLetter() }) return false
        if (URL_REGEX.containsMatchIn(trimmed)) return false
        if (EMAIL_REGEX.containsMatchIn(trimmed)) return false
        return true
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
     * See [resolveLanguageResolution] for the full cascade. Briefly: the target
     * is [TranslationSettings.targetLanguage] else [systemLanguage]; the source
     * is this book's choice, the account choice, [bookLanguage], a detection over
     * [selectedText], a detection over the page, else a refusal.
     *
     * Both sides are normalised to a 2-letter code. When the resolved codes are
     * equal the call returns `null` — the caller must inform rather than ask,
     * because a self-pair is always an echo.
     */
    fun resolveLangPair(
        settings: TranslationSettings,
        bookLanguage: String?,
        systemLanguage: String,
        selectedText: String,
        bookSourceLanguage: String? = null,
        pageText: String? = null,
    ): String? {
        val resolved =
            resolveLanguages(
                settings,
                bookLanguage,
                systemLanguage,
                selectedText,
                bookSourceLanguage,
                pageText,
            )
        return resolved?.let { langPair(it.source, it.target) }
    }

    /**
     * Same resolution as [resolveLangPair] but keeping source and target apart,
     * because [com.nexo.domain.repository.TranslationRepository.translate] takes
     * them as two arguments. The repository re-joins them into the `langpair`,
     * so the pair string a caller reads back is identical.
     *
     * Kept as the collision-only convenience over [resolveLanguageResolution]:
     * its `null` is exactly [NoTranslationReason.SAME_LANGUAGE], which is all a
     * caller with no message to render needs.
     */
    fun resolveLanguages(
        settings: TranslationSettings,
        bookLanguage: String?,
        systemLanguage: String,
        selectedText: String,
        bookSourceLanguage: String? = null,
        pageText: String? = null,
    ): ResolvedLanguages? =
        resolveLanguageResolution(
            settings,
            bookLanguage,
            systemLanguage,
            selectedText,
            bookSourceLanguage,
            pageText,
        ).pair

    /**
     * Full resolution, KEEPING the reason when there is no pair.
     *
     * [resolveLanguages] answers "is there a pair?" and collapses every refusal
     * to `null`. That is all a caller needs when it has nothing to say, but it is
     * not enough for a panel that must name the refusal: a language-pair
     * collision and a target that could not be used are different truths with
     * different fixes, and `null` cannot tell them apart.
     *
     * This is the same cascade as [resolveLanguages] — same inputs, same order,
     * same normalisation — so the pair it yields is identical and the existing
     * path is preserved exactly. It only adds the reason the older shape threw
     * away. The reason space is deliberately larger than the one refusal the
     * resolver produces today, so a detection that later refuses
     * ([NoTranslationReason.UNKNOWN_SOURCE]) and an unusable configured side
     * ([NoTranslationReason.INVALID_SOURCE] / [NoTranslationReason.INVALID_TARGET])
     * have a place to land WITHOUT changing today's behaviour.
     *
     * Today the reachable reasons are [NoTranslationReason.SAME_LANGUAGE] (a
     * collision) and [NoTranslationReason.UNKNOWN_SOURCE] (nothing could name
     * the source, once the selection AND the page were both tried). A configured
     * but unusable language is silently ignored rather than refused.
     *
     * ## The source cascade (Group 2)
     *
     * Top to bottom, first hit wins:
     *
     * 1. **[bookSourceLanguage]** — the reader's choice for THIS book. A
     *    non-null value here outranks everything else, including the account
     *    choice: it is the most specific statement of intent, made while looking
     *    at this book.
     * 2. **[TranslationSettings.sourceLanguage]** — the account choice.
     * 3. **[bookLanguage]** — the book's own metadata.
     * 4. **A guess over [selectedText]** — [guessSourceLanguage], which may now
     *    return `null` (see its `mayFail` note). This is the SELECTION.
     * 5. **A guess over [pageText]** — the same detector, run over the page.
     *    This is a SEPARATE call. Joining the two into one string is forbidden
     *    (invariant 5); see the block at the call site for the measurement.
     * 6. **Give up** → [NoTranslationReason.UNKNOWN_SOURCE]. The resolver can
     *    refuse on a detection failure, not merely on a collision.
     *
     * The target cascade is untouched: [TranslationSettings.targetLanguage] when
     * set, else [systemLanguage]. There is no per-book target and there must
     * never be one (invariant 1).
     */
    fun resolveLanguageResolution(
        settings: TranslationSettings,
        bookLanguage: String?,
        systemLanguage: String,
        selectedText: String,
        bookSourceLanguage: String? = null,
        pageText: String? = null,
    ): TranslationResolution {
        val target = normalizeLanguage(settings.targetLanguage ?: systemLanguage)

        val source =
            sourcedLanguage(bookSourceLanguage, SourceOrigin.BOOK_PREFERENCE)
                ?: sourcedLanguage(settings.sourceLanguage, SourceOrigin.PREFERENCE)
                ?: sourcedLanguage(bookLanguage, SourceOrigin.BOOK)
                ?: detectSource(selectedText)?.let { SourcedLanguage(it, SourceOrigin.DETECTED) }
                ?: detectSource(pageText)?.let { SourcedLanguage(it, SourceOrigin.DETECTED_FROM_PAGE) }
                ?: return TranslationResolution(
                    pair = null,
                    reason = NoTranslationReason.UNKNOWN_SOURCE,
                )

        if (source.code == target) {
            return TranslationResolution(
                pair = null,
                reason = NoTranslationReason.SAME_LANGUAGE,
            )
        }
        return TranslationResolution(
            pair = ResolvedLanguages(source = source.code, target = target),
            reason = null,
            sourceOrigin = source.origin,
        )
    }

    /**
     * One configured cascade step: [tag] normalised to a usable code, labelled
     * with [origin], or `null` when [tag] names nothing so the next step runs.
     */
    private fun sourcedLanguage(
        tag: String?,
        origin: SourceOrigin,
    ): SourcedLanguage? = usableLanguage(tag)?.let { SourcedLanguage(it, origin) }

    /** An interim source plus where it came from, kept local to the cascade. */
    private data class SourcedLanguage(
        val code: String,
        val origin: SourceOrigin,
    )

    /**
     * Normalises [tag] to a usable 2-letter code, or `null` when it names
     * nothing. `null`, blank and unusable input all yield `null`, which is what
     * lets each cascade step fall through to the next instead of pinning a
     * placeholder.
     */
    private fun usableLanguage(tag: String?): String? = normalizeLanguage(tag = tag, fallback = "").takeIf { it.isNotEmpty() }

    /**
     * One detection attempt, over ONE text. Never joins two texts: the string it
     * receives is exactly one of the selection or the page.
     *
     * ## Why the two detections are separate (measured on desktop)
     *
     * A 34-word English quote alone detects `en`; the 4,638-word Spanish page it
     * sits on alone detects `es`; the two CONCATENATED detect `es` — the page
     * drowns the quote. The selection is the more specific evidence and must be
     * asked first, on its own. Calling this twice, once per text, is what keeps a
     * short quote's answer alive. Passing a joined string here would silently
     * invert the preference, so the call sites pass one text each.
     */
    private fun detectSource(text: String?): String? {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return guessSourceLanguage(trimmed, fallback = null)
    }

    private val BASE_LANGUAGE_REGEX = Regex("[a-z]{2}")

    /** A selection shorter than this is treated as an accidental grab. */
    const val MIN_TRANSLATABLE_LENGTH = 2

    /**
     * Matches an HTML entity: `&laquo;`, `&#171;`, `&#xAB;`. A bare `&` with no
     * closing semicolon (`AT&T`, `R&D`, `&amp`) does not match — that is the
     * false positive this pattern exists to avoid.
     */
    private val HTML_ENTITY_REGEX = Regex("&(?:[a-zA-Z][a-zA-Z0-9]*|#[0-9]+|#[xX][0-9a-fA-F]+);")

    /** `http://`, `https://`, or a bare `www.` host — none of it is book prose. */
    private val URL_REGEX = Regex("""(?i)(?:https?://|www\.)\S+""")

    /** Deliberately loose: a local part, an `@`, a dotted domain. */
    private val EMAIL_REGEX = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

    /**
     * Cheap heuristic language detector used only when [Book.language] is
     * unknown. Deliberately tiny: it decides between Spanish and English (the
     * two languages the app ships) and reports failure when it cannot.
     *
     * Signals typical of Spanish and absent from English: `ñ`, and the
     * high-frequency function words `el/la/los/las/de/que/y/una/por`. A
     * Spanish word like `francés` matches on the accent-bearing suffix; an
     * English word matches neither and fails.
     *
     * ## Why it can now return `null` (invariant 8)
     *
     * It used to end with `return fallback`, defaulting to the TARGET. That is
     * the exact input that made the page fallback unreachable: if the detector
     * always answers, the cascade never consults the page, because step 4 has
     * "succeeded". A detector that guesses is still allowed — but a resolver that
     * cannot refuse is not, and the page fallback exists precisely for the case
     * the selection cannot settle.
     *
     * Returning `null` is what makes the page fallback meaningful, and it is also
     * what makes an honest [NoTranslationReason.UNKNOWN_SOURCE] possible when
     * NEITHER the selection nor the page carries a marker. The old
     * always-the-target fallback would instead have handed the reader a
     * confidently wrong pair — the source silently becoming the target, which is
     * the collision that produces a bogus refusal anyway.
     *
     * @param fallback returned when no marker is found. Defaults to `null`, so
     *   the caller sees a detection FAILURE rather than a guess. Callers that
     *   genuinely want a guess can still pass a fallback.
     */
    fun guessSourceLanguage(
        text: String,
        fallback: String? = null,
    ): String? {
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

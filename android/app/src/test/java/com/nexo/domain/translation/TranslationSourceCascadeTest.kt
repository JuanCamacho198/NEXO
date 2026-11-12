package com.nexo.domain.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Group 2's model, pinned at the resolver.
 *
 * Three things this file exists to keep true:
 *
 * 1. **The per-book source exists and outranks the account's.** The first
 *    per-book level Android has ever had must be SOURCE-only: no per-book target
 *    may appear in the signature, the storage or the cascade (invariant 1).
 * 2. **An absent per-book choice FALLS THROUGH.** `null` means "no choice for
 *    this book", not "automatic" — the account preference and then the rest of
 *    the ladder must still be consulted.
 * 3. **The page fallback fires only when the selection yields nothing, and the
 *    two detections are SEPARATE.** The tests below include one that would FAIL
 *    if the two texts were ever concatenated, because that is the exact
 *    regression the desktop measurement recorded.
 */
class TranslationSourceCascadeTest {
    // ── 1. The per-book choice wins over the account choice ───────────────

    @Test
    fun `the per-book source outranks the account source`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(sourceLanguage = "pt"),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "hola",
                bookSourceLanguage = "es",
            )

        assertEquals("es", resolution.pair?.source)
        assertEquals("en", resolution.pair?.target)
        assertEquals(SourceOrigin.BOOK_PREFERENCE, resolution.sourceOrigin)
    }

    @Test
    fun `the per-book source outranks the book language and the selection`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "el libro de la vida",
                bookSourceLanguage = "de",
            )

        assertEquals("de", resolution.pair?.source)
        assertEquals(SourceOrigin.BOOK_PREFERENCE, resolution.sourceOrigin)
    }

    // ── 2. An absent choice falls through to the account ──────────────────

    @Test
    fun `an absent per-book source falls through to the account source`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(sourceLanguage = "pt"),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "hola",
                bookSourceLanguage = null,
            )

        assertEquals("pt", resolution.pair?.source)
        assertEquals(SourceOrigin.PREFERENCE, resolution.sourceOrigin)
    }

    @Test
    fun `a blank per-book source is treated as absent, not as a language`() {
        // "Automatic (detect)" clears the key rather than storing a placeholder;
        // a blank value that reaches the resolver must behave the same way.
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(sourceLanguage = "pt"),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "hola",
                bookSourceLanguage = "   ",
            )

        assertEquals("pt", resolution.pair?.source)
        assertEquals(SourceOrigin.PREFERENCE, resolution.sourceOrigin)
    }

    @Test
    fun `with neither choice the book language names the source`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "bonjour",
                bookSourceLanguage = null,
            )

        assertEquals("fr", resolution.pair?.source)
        assertEquals(SourceOrigin.BOOK, resolution.sourceOrigin)
    }

    // ── 3. The page fallback ──────────────────────────────────────────────

    @Test
    fun `the selection names the source before the page is consulted`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "fr",
                selectedText = "the book of life",
                pageText = "el libro de la vida es largo y profundo",
            )

        assertEquals("en", resolution.pair?.source)
        assertEquals(SourceOrigin.DETECTED, resolution.sourceOrigin)
    }

    @Test
    fun `the page names the source when the selection yields nothing`() {
        // The measured case this feature exists for: a short quote with no
        // marker of its own, on a page that has plenty of evidence.
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "Odiseo",
                pageText = "el libro de la vida es largo y profundo",
            )

        assertEquals("es", resolution.pair?.source)
        assertEquals(SourceOrigin.DETECTED_FROM_PAGE, resolution.sourceOrigin)
    }

    @Test
    fun `the page is not consulted when a configured source exists`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "de",
                systemLanguage = "en",
                selectedText = "Odiseo",
                pageText = "el libro de la vida es largo y profundo",
            )

        assertEquals("de", resolution.pair?.source)
        assertEquals(SourceOrigin.BOOK, resolution.sourceOrigin)
    }

    /**
     * The no-concatenation invariant (invariant 5), expressed as a test that
     * FAILS if the two texts are joined.
     *
     * The measurement it reproduces is desktop's: a 34-word English quote alone
     * detects `en`; the long Spanish page alone detects `es`; the two CONCATENATED
     * detect `es`, because the page drowns the quote. The resolver must keep the
     * selection's answer, so the expected source here is `en` — the value a
     * joined implementation cannot produce.
     */
    @Test
    fun `the selection and the page are detected separately, never joined`() {
        val quote =
            "The quick brown fox jumps over the lazy dog and then it rests, because the " +
                "hunt was long and the day was warm and the forest was quiet."
        val page =
            "el libro de la vida es largo y profundo y la memoria de los hombres " +
                "es corta y olvidadiza, por lo que conviene escribir las cosas una vez " +
                "para que no se pierdan con el tiempo ni con la costumbre"

        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "de",
                selectedText = quote,
                pageText = page,
            )

        // The selection's verdict wins. A joined implementation returns "es"
        // here, because the page's markers outnumber the quote's.
        assertEquals(
            "the selection must be detected on its own; joining it with the page inverts the answer",
            "en",
            resolution.pair?.source,
        )
    }

    @Test
    fun `an empty selection and an empty page refuse as an unknown source`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "",
                pageText = "",
            )

        assertNull("nothing resolved", resolution.pair)
        assertEquals(NoTranslationReason.UNKNOWN_SOURCE, resolution.reason)
    }

    @Test
    fun `a markerless selection with no page refuses instead of guessing`() {
        // The honest refusal invariant (invariant 8): the detector may guess, but
        // the resolver must be able to refuse. Without this, the old
        // always-the-target fallback would have produced `en|en` — a wrong pair
        // that reads as a bogus collision.
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "reverberation",
                pageText = null,
            )

        assertNull(resolution.pair)
        assertEquals(NoTranslationReason.UNKNOWN_SOURCE, resolution.reason)
    }

    // ── The destination is never per-book (invariant 1) ───────────────────

    @Test
    fun `no per-book target exists so the target has no per-book input`() {
        // There is no `bookTargetLanguage` parameter, by construction. This test
        // documents that the asymmetry is deliberate: two books sharing an
        // account resolve the SAME target regardless of any book-level input.
        val settings = TranslationSettings(targetLanguage = "fr")

        val first =
            TranslationPolicy.resolveLanguageResolution(
                settings = settings,
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
                bookSourceLanguage = "es",
            )
        val second =
            TranslationPolicy.resolveLanguageResolution(
                settings = settings,
                bookLanguage = "de",
                systemLanguage = "en",
                selectedText = "hallo",
                bookSourceLanguage = "de",
            )

        assertEquals("fr", first.pair?.target)
        assertEquals("fr", second.pair?.target)
    }

    // ── The source origin the bar renders ────────────────────────────────

    @Test
    fun `a chosen source is not marked as an inference`() {
        val bookChoice =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "hola",
                bookSourceLanguage = "es",
            )
        val accountChoice =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(sourceLanguage = "es"),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "hola",
            )

        assertEquals(false, bookChoice.sourceOrigin?.isInferred)
        assertEquals(false, accountChoice.sourceOrigin?.isInferred)
    }

    @Test
    fun `an inferred source is marked so the bar can label it Auto`() {
        val fromBook =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        val fromSelection =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "el libro de la vida",
            )
        val fromPage =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "fr",
                selectedText = "Odiseo",
                pageText = "the book of life is long and deep",
            )

        assertEquals(true, fromBook.sourceOrigin?.isInferred)
        assertEquals(true, fromSelection.sourceOrigin?.isInferred)
        assertEquals(true, fromPage.sourceOrigin?.isInferred)
    }

    @Test
    fun `a resolution carries a source origin exactly when it carries a pair`() {
        val resolved =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertEquals(true, resolved.pair != null && resolved.sourceOrigin != null)

        val refused =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "reverberation",
            )
        assertNull(refused.pair)
        assertNull(refused.sourceOrigin)
    }
}

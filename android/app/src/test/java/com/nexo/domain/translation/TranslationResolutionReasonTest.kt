package com.nexo.domain.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The typed refusal: a resolution must be able to say WHICH refusal it is, and
 * the collision must map to [NoTranslationReason.SAME_LANGUAGE] rather than a
 * generic "the text could not be translated".
 *
 * The bug this pins, measured live: selecting "pedirle" in a Spanish book with
 * the app UI in Spanish (device English) rendered "No hay traducción disponible
 * para este texto" — blaming a text that was perfectly translatable, because the
 * pair WAS the collision. The reason is what lets the panel say so.
 *
 * The second half of this file is the guard rail: adding the reason type must not
 * move ANY existing resolution. Every case the pre-change resolver answered is
 * asserted through [TranslationPolicy.resolveLanguages] (which now delegates) and
 * through the typed call, and the two must agree.
 */
class TranslationResolutionReasonTest {
    // ── The collision carries SAME_LANGUAGE, not a generic refusal ─────────

    @Test
    fun `a source equal to the target refuses with the same-language reason`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "es",
                selectedText = "pedirle",
            )

        assertNull("a self-pair must not produce a pair", resolution.pair)
        assertEquals(NoTranslationReason.SAME_LANGUAGE, resolution.reason)
    }

    @Test
    fun `the measured trap refuses with the same-language reason when the target follows the app locale`() {
        // Device en-US would translate; the app locale (es) collides. This is the
        // reason the panel must be able to tell apart from a missing translation.
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "es-ES",
                selectedText = "pedirle",
            )

        assertEquals(NoTranslationReason.SAME_LANGUAGE, resolution.reason)
    }

    @Test
    fun `an explicit same source and target refuses with the same-language reason`() {
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(sourceLanguage = "es", targetLanguage = "es-ES"),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "hola",
            )

        assertEquals(NoTranslationReason.SAME_LANGUAGE, resolution.reason)
    }

    @Test
    fun `the reason type expresses a real detection failure`() {
        // Group 2: the detector can now fail, so UNKNOWN_SOURCE is REACHABLE.
        // A markerless selection with no page to fall back on refuses here — not
        // as a collision, and not with a guessed pair.
        val resolution =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "reverberation",
            )
        assertNull("nothing could name the source", resolution.pair)
        assertEquals(NoTranslationReason.UNKNOWN_SOURCE, resolution.reason)
    }

    @Test
    fun `a resolution never carries both a pair and a reason, nor neither`() {
        val translated =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertEquals(null, translated.reason)
        assertEquals("es", translated.pair?.source)

        val refused =
            TranslationPolicy.resolveLanguageResolution(
                settings = TranslationSettings(),
                bookLanguage = "en",
                systemLanguage = "en",
                selectedText = "reverberation",
            )
        assertNull(refused.pair)
        assertEquals(NoTranslationReason.SAME_LANGUAGE, refused.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a resolution with neither a pair nor a reason is rejected`() {
        TranslationResolution(pair = null, reason = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a resolution with both a pair and a reason is rejected`() {
        TranslationResolution(
            pair = ResolvedLanguages(source = "es", target = "en"),
            reason = NoTranslationReason.SAME_LANGUAGE,
        )
    }

    // ── The existing path is byte-for-byte unchanged ──────────────────────
    //
    // Every case from TranslationLanguageResolutionTest, asserted through BOTH
    // calls, so the typed variant can never drift from the collision-only one.

    @Test
    fun `the typed and the collision-only resolution agree on every existing case`() {
        val cases =
            listOf(
                // settings, bookLanguage, systemLanguage, selectedText, expected pair
                ResolutionCase(TranslationSettings(), "es", "en", "hola", "es|en"),
                ResolutionCase(TranslationSettings(targetLanguage = "fr"), "es", "en", "hola", "es|fr"),
                ResolutionCase(TranslationSettings(sourceLanguage = "pt"), "es", "en", "hola", "pt|en"),
                ResolutionCase(TranslationSettings(), "fr", "en", "bonjour", "fr|en"),
                ResolutionCase(TranslationSettings(), null, "en", "el libro de la vida", "es|en"),
                ResolutionCase(TranslationSettings(), "  ", "en", "el libro de la vida", "es|en"),
                ResolutionCase(TranslationSettings(), "en", "en", "reverberation", null),
                ResolutionCase(TranslationSettings(sourceLanguage = "es", targetLanguage = "es-ES"), "fr", "en", "hola", null),
                ResolutionCase(TranslationSettings(), null, "es", "el libro de la vida", null),
            )

        cases.forEach { case ->
            val legacy =
                TranslationPolicy
                    .resolveLanguages(case.settings, case.bookLanguage, case.systemLanguage, case.selectedText)
                    ?.let { "${it.source}|${it.target}" }
            val typedResolution =
                TranslationPolicy.resolveLanguageResolution(
                    case.settings,
                    case.bookLanguage,
                    case.systemLanguage,
                    case.selectedText,
                )
            val typed = typedResolution.pair?.let { "${it.source}|${it.target}" }

            assertEquals("legacy and typed must agree", legacy, typed)
            assertEquals("case ${case.bookLanguage}/${case.systemLanguage}", case.expectedPair, typed)
            assertEquals(
                "a null pair must always carry the collision reason",
                typed == null,
                typedResolution.reason == NoTranslationReason.SAME_LANGUAGE,
            )
        }
    }

    private data class ResolutionCase(
        val settings: TranslationSettings,
        val bookLanguage: String?,
        val systemLanguage: String,
        val selectedText: String,
        val expectedPair: String?,
    )
}

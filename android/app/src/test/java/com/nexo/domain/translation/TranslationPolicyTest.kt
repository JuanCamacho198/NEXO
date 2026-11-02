package com.nexo.domain.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPolicyTest {
    // ── isRealTranslation: the `francés` defect ──────────────────────

    @Test
    fun `isRealTranslation returns false when API echoes source identically`() {
        // Verified against the live MyMemory API: `francés` came back as
        // `francés` with match 0.99. The match score cannot be trusted.
        assertFalse(TranslationPolicy.isRealTranslation("francés", "francés"))
    }

    @Test
    fun `isRealTranslation returns false for case-and-space echo`() {
        assertFalse(TranslationPolicy.isRealTranslation("francés", "  Francés "))
    }

    @Test
    fun `isRealTranslation returns true for a real translation`() {
        assertTrue(TranslationPolicy.isRealTranslation("francés", "French"))
    }

    @Test
    fun `isRealTranslation returns false for blank output`() {
        assertFalse(TranslationPolicy.isRealTranslation("hola", ""))
        assertFalse(TranslationPolicy.isRealTranslation("hola", "   "))
    }

    // ── Language normalisation and langpair ──────────────────────────

    @Test
    fun `normalizeLanguage reduces a region tag to its base`() {
        assertEquals("es", TranslationPolicy.normalizeLanguage("es-ES"))
        assertEquals("es", TranslationPolicy.normalizeLanguage("es_419"))
        assertEquals("en", TranslationPolicy.normalizeLanguage("EN"))
    }

    @Test
    fun `normalizeLanguage falls back when the tag is unusable`() {
        assertEquals("en", TranslationPolicy.normalizeLanguage(null))
        assertEquals("en", TranslationPolicy.normalizeLanguage(""))
        assertEquals("fr", TranslationPolicy.normalizeLanguage("garbage", fallback = "fr"))
    }

    @Test
    fun `langPair joins normalized codes with a pipe`() {
        assertEquals("es|en", TranslationPolicy.langPair("es-ES", "en-US"))
    }

    // ── Cache decision ───────────────────────────────────────────────

    @Test
    fun `cachedTranslationOrNull returns the value on an exact hit`() {
        val cached = CachedTranslation(original = "hola", langPair = "es|en", translated = "hello")
        assertEquals("hello", TranslationPolicy.cachedTranslationOrNull(cached, "hola", "es|en"))
    }

    @Test
    fun `cachedTranslationOrNull rejects a different langpair`() {
        val cached = CachedTranslation(original = "hola", langPair = "es|fr", translated = "bonjour")
        assertNull(TranslationPolicy.cachedTranslationOrNull(cached, "hola", "es|en"))
    }

    @Test
    fun `cachedTranslationOrNull rejects a different source text`() {
        val cached = CachedTranslation(original = "hola", langPair = "es|en", translated = "hello")
        assertNull(TranslationPolicy.cachedTranslationOrNull(cached, "adiós", "es|en"))
    }

    @Test
    fun `cachedTranslationOrNull rejects a blank stored translation`() {
        val cached = CachedTranslation(original = "hola", langPair = "es|en", translated = "   ")
        assertNull(TranslationPolicy.cachedTranslationOrNull(cached, "hola", "es|en"))
    }

    @Test
    fun `cachedTranslationOrNull returns null when there is no cache row`() {
        assertNull(TranslationPolicy.cachedTranslationOrNull(null, "hola", "es|en"))
    }

    // ── Heuristic source detection (only when Book.language is unknown) ──

    @Test
    fun `guessSourceLanguage detects Spanish from an accented word`() {
        assertEquals("es", TranslationPolicy.guessSourceLanguage("francés"))
    }

    @Test
    fun `guessSourceLanguage detects Spanish from a function word`() {
        assertEquals("es", TranslationPolicy.guessSourceLanguage("el libro de la vida"))
    }

    @Test
    fun `guessSourceLanguage detects English from a function word`() {
        assertEquals("en", TranslationPolicy.guessSourceLanguage("the book of life"))
    }

    @Test
    fun `guessSourceLanguage defaults to the fallback for a bare word`() {
        assertEquals("en", TranslationPolicy.guessSourceLanguage("book"))
        assertEquals("fr", TranslationPolicy.guessSourceLanguage("", fallback = "fr"))
    }
}

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

    // ── isRealTranslation: the `&laquo;` defect (T2) ─────────────────
    //
    // Measured against the live MyMemory API: `«` came back as `&laquo;`.
    // That is HTML markup, not a translation, so the panel must not show it.

    @Test
    fun `isRealTranslation returns false when the output is an HTML entity`() {
        assertFalse(TranslationPolicy.isRealTranslation("«", "&laquo;"))
    }

    @Test
    fun `isRealTranslation returns false for a numeric HTML entity`() {
        assertFalse(TranslationPolicy.isRealTranslation("«", "&#171;"))
        assertFalse(TranslationPolicy.isRealTranslation("«", "&#xAB;"))
    }

    @Test
    fun `isRealTranslation does not reject a bare ampersand`() {
        // `AT&T` contains `&` but is not an entity: the rule matches the
        // entity PATTERN (`&name;` / `&#123;`), never a lone `&`.
        assertTrue(TranslationPolicy.isRealTranslation("hola", "AT&T"))
        assertTrue(TranslationPolicy.isRealTranslation("y", "and"))
        assertTrue(TranslationPolicy.isRealTranslation("tomate & cebolla", "tomato & onion"))
    }

    @Test
    fun `isRealTranslation does not reject an ampersand without a semicolon`() {
        assertTrue(TranslationPolicy.isRealTranslation("hola", "R&D"))
        // A name-like token but no closing `;` is not an entity.
        assertTrue(TranslationPolicy.isRealTranslation("hola", "&amp"))
    }

    // ── isWorthTranslating: the input guard (T1) ─────────────────────
    //
    // The eight cases below were measured against the live MyMemory API.
    // Five were pure waste and two produced actively bad output:
    //   `.`   → `bility`    (invented a word)
    //   `«`   → `&laquo;`   (HTML entity)
    //   `—`   → `—`, `"  "` → `"  "`, `a` → `a`, `123` → `123`, `¿?` → `?`
    //   `y`   → `and`       (correct, but a single character)

    @Test
    fun `isWorthTranslating rejects the punctuation that invented a word`() {
        assertFalse(TranslationPolicy.isWorthTranslating("."))
    }

    @Test
    fun `isWorthTranslating rejects the character that returned an HTML entity`() {
        assertFalse(TranslationPolicy.isWorthTranslating("«"))
    }

    @Test
    fun `isWorthTranslating rejects a string with no letters`() {
        assertFalse(TranslationPolicy.isWorthTranslating("—"))
        assertFalse(TranslationPolicy.isWorthTranslating("123"))
        assertFalse(TranslationPolicy.isWorthTranslating("¿?"))
    }

    @Test
    fun `isWorthTranslating rejects whitespace-only selections`() {
        assertFalse(TranslationPolicy.isWorthTranslating("  "))
        assertFalse(TranslationPolicy.isWorthTranslating(""))
        assertFalse(TranslationPolicy.isWorthTranslating("   \n\t "))
    }

    @Test
    fun `isWorthTranslating rejects a single character even when it is a word`() {
        // `y` → `and` is a CORRECT translation, but a one-character selection
        // is dominated by accidental drag-handle selections. The length rule
        // is kept (see the KDoc on `isWorthTranslating`); this test pins the
        // documented trade-off instead of leaving it implicit.
        assertFalse(TranslationPolicy.isWorthTranslating("y"))
        assertFalse(TranslationPolicy.isWorthTranslating("a"))
    }

    @Test
    fun `isWorthTranslating accepts real book text`() {
        assertTrue(TranslationPolicy.isWorthTranslating("El extranjero"))
        assertTrue(TranslationPolicy.isWorthTranslating("no entendía nada"))
        assertTrue(TranslationPolicy.isWorthTranslating("reverberación"))
        assertTrue(TranslationPolicy.isWorthTranslating("  hola  "))
    }

    @Test
    fun `isWorthTranslating accepts a single letter word of two characters`() {
        assertTrue(TranslationPolicy.isWorthTranslating("ab"))
    }

    @Test
    fun `isWorthTranslating rejects a URL`() {
        assertFalse(TranslationPolicy.isWorthTranslating("https://example.com/page"))
        assertFalse(TranslationPolicy.isWorthTranslating("www.example.com"))
    }

    @Test
    fun `isWorthTranslating rejects an email address`() {
        assertFalse(TranslationPolicy.isWorthTranslating("someone@example.com"))
    }

    @Test
    fun `isWorthTranslating does not reject text that merely mentions a domain-like word`() {
        assertTrue(TranslationPolicy.isWorthTranslating("el extranjero dijo hola"))
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

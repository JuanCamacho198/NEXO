package com.nexo.domain.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1 — pure language resolution for a reader selection.
 *
 * The whole point of this layer is the case that fails today: a book in English
 * on an English phone resolved to `en|en`, so MyMemory echoed the source and the
 * panel said "No translation available" to a legitimate user. Resolution must
 * therefore return `null` (NO_LANGPAIR) whenever the resolved source equals the
 * resolved target, and the caller is expected to inform instead of asking.
 */
class TranslationLanguageResolutionTest {
    // ── Target resolution ────────────────────────────────────────────────

    @Test
    fun `target falls back to the system language when the preference is unset`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertEquals("es|en", pair)
    }

    @Test
    fun `an explicit target preference overrides the system language`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(targetLanguage = "fr"),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertEquals("es|fr", pair)
    }

    // ── Source resolution ────────────────────────────────────────────────

    @Test
    fun `an explicit source preference wins over the book language`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(sourceLanguage = "pt"),
                bookLanguage = "es",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertEquals("pt|en", pair)
    }

    @Test
    fun `source falls back to the book language when no preference is set`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "bonjour",
            )
        assertEquals("fr|en", pair)
    }

    @Test
    fun `source falls back to the selected text when the book language is unknown`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "en",
                selectedText = "el libro de la vida",
            )
        assertEquals("es|en", pair)
    }

    @Test
    fun `an unusable book language is ignored in favour of the selected text`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = "  ",
                systemLanguage = "en",
                selectedText = "el libro de la vida",
            )
        assertEquals("es|en", pair)
    }

    // ── The identity guard (NO_LANGPAIR) ─────────────────────────────────

    @Test
    fun `resolved source equal to resolved target yields no langpair`() {
        // The verified defect: an English book on an English phone.
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = "en",
                systemLanguage = "en",
                selectedText = "reverberation",
            )
        assertNull(pair)
    }

    @Test
    fun `no langpair when the user explicitly picks the same source and target`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(sourceLanguage = "es", targetLanguage = "es-ES"),
                bookLanguage = "fr",
                systemLanguage = "en",
                selectedText = "hola",
            )
        assertNull(pair)
    }

    @Test
    fun `no langpair when the guessed source matches the target`() {
        val pair =
            TranslationPolicy.resolveLangPair(
                settings = TranslationSettings(),
                bookLanguage = null,
                systemLanguage = "es",
                selectedText = "el libro de la vida",
            )
        assertNull(pair)
    }
}

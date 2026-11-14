package com.nexo.presentation.viewmodel.reader

import com.nexo.domain.translation.TranslationPolicy
import com.nexo.domain.translation.TranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The regression the "follow the device" defect was: the translation TARGET
 * resolved to the app's UI locale instead of the reader's real device locale.
 *
 * ## Which API is immune — and the candidate that was NOT
 *
 * `AppCompatDelegate.setApplicationLocales` overrides `Locale.getDefault()` for the
 * process. The first candidate for the device locale was
 * `Resources.getSystem().configuration`, and it was MEASURED WRONG on the project's
 * AVD (Android 15 / API 35, device `en-US`, app locale `[es]`):
 * `Resources.getSystem().configuration.locales` returned `[es, en_US]` — the
 * override leaked into the "system" resources and its first entry was still `es`.
 * The chosen API is `LocaleManager.getSystemLocales()` (API 33+), which returned
 * `[en_US]` in the same measurement.
 *
 * These tests pin the DECISION (which the acquisition feeds). The locale list is an
 * explicit input precisely so the bug is reproducible without a device or
 * Robolectric: a plain locale list stands in for the device, and
 * `Locale.getDefault()` is deliberately set to something else to model the
 * per-app override.
 */
class DeviceLocaleTest {
    @Test
    fun `the device tag is read from the system locales, never from the overridden default locale`() {
        val previousDefault = Locale.getDefault()
        try {
            // The trap: the reader set the app's UI to Spanish, which overrides
            // the process default. The DEVICE is still English.
            Locale.setDefault(Locale.forLanguageTag("es"))

            val tag = DeviceLocale.languageTag(systemLocales = listOf(Locale.forLanguageTag("en-US")))

            assertEquals("en-US", tag)
        } finally {
            Locale.setDefault(previousDefault)
        }
    }

    @Test
    fun `a Spanish device reports Spanish regardless of an English app locale`() {
        val previousDefault = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en"))

            val tag = DeviceLocale.languageTag(systemLocales = listOf(Locale.forLanguageTag("es-ES")))

            assertEquals("es-ES", tag)
        } finally {
            Locale.setDefault(previousDefault)
        }
    }

    @Test
    fun `the most specific system locale wins`() {
        // The measured contamination shape: [es, en_US]. The DEVICE answer is the
        // locale service's own list; this pins that the FIRST entry is what is read,
        // so a caller must hand over the DEVICE's list (en_US first), not a
        // resources list an override may have reordered.
        val tag =
            DeviceLocale.languageTag(
                systemLocales = listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("es")),
            )

        assertEquals("en-US", tag)
    }

    @Test
    fun `an empty device locale list falls back to the policy default language`() {
        val tag = DeviceLocale.languageTag(systemLocales = emptyList())

        assertEquals(TranslationPolicy.DEFAULT_LANGUAGE, tag)
    }

    // ── The end-to-end regression, through the resolver ───────────────────
    //
    // The measured trap: device `en-US`, app locale `[es]`, Spanish book. The
    // target used to come from the app locale (`es`), collide with the book's
    // `es` source, and refuse with no network call. With the device locale the
    // pair resolves and the translation proceeds.

    @Test
    fun `a Spanish book on an English device resolves and translates while the app UI is Spanish`() {
        val previousDefault = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es"))

            val deviceLanguage =
                DeviceLocale.languageTag(systemLocales = listOf(Locale.forLanguageTag("en-US")))

            val resolution =
                TranslationPolicy.resolveLanguageResolution(
                    settings = TranslationSettings(),
                    bookLanguage = "es",
                    systemLanguage = deviceLanguage,
                    selectedText = "pedirle",
                )

            assertEquals("es|en", resolution.pair?.let { "${it.source}|${it.target}" })
            assertEquals(null, resolution.reason)
        } finally {
            Locale.setDefault(previousDefault)
        }
    }
}

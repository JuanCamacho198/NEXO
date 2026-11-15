package com.nexo.data.session

import android.content.Context
import android.content.SharedPreferences
import com.nexo.domain.translation.TranslationSettings
import com.nexo.testutil.activeUserProviderFor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T2 — [TranslationPreferences] must be an account-scoped store, following the
 * same file-name isolation as [ReaderPreferences] and the anonymous rule of
 * decision D5 (no owner ⇒ read defaults, write nothing).
 */
class TranslationPreferencesTest {
    private fun prefsWith(
        stored: Map<String, String> = emptyMap(),
    ): TranslationPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { getString(any(), any()) } answers {
                    stored[firstArg<String>()] ?: secondArg()
                }
                every { edit() } returns editor
            }
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        return TranslationPreferences(context, activeUserProviderFor())
    }

    private fun translationPreferencesFor(
        stored: Map<String, String> = emptyMap(),
        userId: String?,
    ): TranslationPreferences {
        val prefs =
            mockk<SharedPreferences> {
                every { getString(any(), any()) } answers {
                    stored[firstArg<String>()] ?: secondArg()
                }
                every { edit() } returns mockk(relaxed = true)
            }
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        return TranslationPreferences(context, activeUserProviderFor(userId))
    }

    // ── Load ─────────────────────────────────────────────────────────

    @Test
    fun `load returns null languages when nothing is stored`() {
        val settings = prefsWith().load()
        assertNull(settings.targetLanguage)
        assertNull(settings.sourceLanguage)
    }

    @Test
    fun `load reads the persisted languages`() {
        val settings =
            prefsWith(
                mapOf(
                    "target_language" to "fr",
                    "source_language" to "es",
                ),
            ).load()
        assertEquals("fr", settings.targetLanguage)
        assertEquals("es", settings.sourceLanguage)
    }

    // ── Save ─────────────────────────────────────────────────────────

    @Test
    fun `save writes both languages`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { edit() } returns editor
            }
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        TranslationPreferences(context, activeUserProviderFor())
            .save(TranslationSettings(targetLanguage = "de", sourceLanguage = "it"))

        verify { editor.putString("target_language", "de") }
        verify { editor.putString("source_language", "it") }
    }

    @Test
    fun `save with null languages removes the keys`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { edit() } returns editor
            }
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        TranslationPreferences(context, activeUserProviderFor())
            .save(TranslationSettings(targetLanguage = null, sourceLanguage = null))

        verify { editor.remove("target_language") }
        verify { editor.remove("source_language") }
    }

    // ── Account scope (decision D5) ──────────────────────────────────

    @Test
    fun `load returns defaults when there is no active account`() {
        val settings = translationPreferencesFor(userId = null).load()
        assertEquals(TranslationSettings(), settings)
    }

    @Test
    fun `save is a no-op when there is no active account`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { edit() } returns editor
            }
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        TranslationPreferences(context, activeUserProviderFor(null))
            .save(TranslationSettings(targetLanguage = "fr"))

        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `the preferences file is namespaced by the account id`() {
        val userId = "user-a"
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context =
            mockk<Context> {
                every { applicationContext } returns this
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        TranslationPreferences(context, activeUserProviderFor(userId)).load()

        verify { context.getSharedPreferences("nexo_translation_prefs__$userId", Context.MODE_PRIVATE) }
    }
}

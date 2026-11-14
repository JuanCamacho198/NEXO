package com.nexo.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-account DEFINITION language (T9 of `android-define-selection`).
 *
 * The properties that matter, each one test:
 *
 * 1. A chosen language PERSISTS.
 * 2. `null` means "follow the app UI locale" — the ladder is
 *    stored → locale → `en`, and an unusable stored value falls through.
 * 3. The choice is per ACCOUNT: the file name carries the isolation.
 * 4. With no active account the write is a no-op and the read is `null` (D5,
 *    the anonymous reader is read-only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefinitionPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    private fun store(userId: String? = TEST_USER_ID) = DefinitionPreferences(context, providerFor(userId))

    @Test
    fun `a chosen definition language persists`() {
        store().save("fr")

        assertEquals("fr", store().load())
    }

    @Test
    fun `an unchosen definition language reads as no choice`() {
        assertNull(store().load())
    }

    @Test
    fun `saving null removes the choice so the store follows the locale again`() {
        store().save("fr")
        assertEquals("fr", store().load())

        store().save(null)

        assertNull("null is the follow-the-locale state, not a value", store().load())
    }

    @Test
    fun `saving a blank language removes the choice`() {
        store().save("fr")

        store().save("  ")

        assertNull(store().load())
    }

    @Test
    fun `resolve falls back to the app locale when nothing was chosen`() {
        assertEquals("pt", store().resolve("pt"))
    }

    @Test
    fun `resolve prefers the stored choice over the app locale`() {
        store().save("de")

        assertEquals("de", store().resolve("pt"))
    }

    @Test
    fun `resolve falls back to en when neither the store nor the locale is usable`() {
        assertEquals(DefinitionPreferences.DEFAULT_LANGUAGE, store().resolve(null))
        assertEquals(DefinitionPreferences.DEFAULT_LANGUAGE, store().resolve("123"))
    }

    @Test
    fun `resolve ignores an unusable stored value rather than passing it on`() {
        store().save("123")

        assertEquals("es", store().resolve("es"))
    }

    @Test
    fun `the definition language is independent per account`() {
        store(TEST_USER_ID).save("fr")
        store(OTHER_USER_ID).save("de")

        assertEquals("fr", store(TEST_USER_ID).load())
        assertEquals("de", store(OTHER_USER_ID).load())
    }

    @Test
    fun `an account that never chose does not inherit another account's choice`() {
        store(TEST_USER_ID).save("fr")

        assertNull(store(OTHER_USER_ID).load())
    }

    @Test
    fun `a write is a no-op on the anonymous path`() {
        store("local-abc").save("fr")

        assertNull(
            "the throwaway session must persist nothing",
            store(TEST_USER_ID).load(),
        )
    }

    @Test
    fun `the anonymous path reads as no choice`() {
        assertNull(store("local-abc").load())
    }

    @Test
    fun `the anonymous path resolves to the locale, never a stored value`() {
        assertEquals("es", store("local-abc").resolve("es"))
    }

    @Test
    fun `the definition store is separate from the translation store`() {
        // Same file-name convention, different files: writing the definition
        // language must not move the translation target, and vice versa.
        TranslationPreferences(context, providerFor(TEST_USER_ID)).save(
            com.nexo.domain.translation
                .TranslationSettings(targetLanguage = "en"),
        )
        store().save("fr")

        assertEquals("fr", store().load())
        assertEquals(
            "en",
            TranslationPreferences(context, providerFor(TEST_USER_ID)).load().targetLanguage,
        )
    }
}

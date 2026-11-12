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
 * The per-book SOURCE choice (Group 2, T3).
 *
 * Four properties matter, and each is one test:
 *
 * 1. A choice is stored PER BOOK — writing one book's source must not move
 *    another's.
 * 2. An absent choice reads as `null` — "no choice here", which the cascade
 *    falls through. It is NOT "automatic" and must not be stored as a value.
 * 3. Clearing removes the key, so a cleared book is indistinguishable from a
 *    never-chosen one. That is what "Automatic (detect)" means.
 * 4. The choice is per ACCOUNT, because it lives in the account-scoped file —
 *    the same isolation proof the account preference already has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationBookSourcePreferenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    private fun store(userId: String? = TEST_USER_ID) =
        TranslationPreferences(context, providerFor(userId))

    @Test
    fun `a book source choice is stored for that book`() {
        store().saveBookSource(bookId = "book-a", language = "es")

        assertEquals("es", store().bookSource("book-a"))
    }

    @Test
    fun `one book's choice does not leak into another`() {
        store().saveBookSource(bookId = "book-a", language = "es")
        store().saveBookSource(bookId = "book-b", language = "de")

        assertEquals("es", store().bookSource("book-a"))
        assertEquals("de", store().bookSource("book-b"))
        assertNull("a book never chosen must read as no choice", store().bookSource("book-c"))
    }

    @Test
    fun `an unchosen book reads as no choice, not as automatic`() {
        assertNull(store().bookSource("book-never-chosen"))
    }

    @Test
    fun `clearing a book source removes the choice`() {
        store().saveBookSource(bookId = "book-a", language = "es")
        assertEquals("es", store().bookSource("book-a"))

        store().clearBookSource("book-a")

        assertNull("Automatic (detect) must clear the choice, not store a value", store().bookSource("book-a"))
    }

    @Test
    fun `saving a blank language clears the choice`() {
        store().saveBookSource(bookId = "book-a", language = "es")

        store().saveBookSource(bookId = "book-a", language = "  ")

        assertNull(store().bookSource("book-a"))
    }

    @Test
    fun `a book source choice is independent per account`() {
        store(TEST_USER_ID).saveBookSource(bookId = "book-a", language = "es")
        store(OTHER_USER_ID).saveBookSource(bookId = "book-a", language = "de")

        assertEquals("es", store(TEST_USER_ID).bookSource("book-a"))
        assertEquals("de", store(OTHER_USER_ID).bookSource("book-a"))
    }

    @Test
    fun `a book source write is a no-op on the anonymous path`() {
        store("local-abc").saveBookSource(bookId = "book-a", language = "es")

        assertNull("the throwaway session must persist nothing", store(TEST_USER_ID).bookSource("book-a"))
    }

    @Test
    fun `a blank book id is ignored rather than keyed`() {
        store().saveBookSource(bookId = "", language = "es")

        assertNull(store().bookSource(""))
    }

    @Test
    fun `the account preference and the book choice coexist`() {
        // The two levels live in the same file but are separate keys: writing one
        // must never move the other.
        store().save(com.nexo.domain.translation.TranslationSettings(sourceLanguage = "pt", targetLanguage = "en"))
        store().saveBookSource(bookId = "book-a", language = "es")

        assertEquals("pt", store().load().sourceLanguage)
        assertEquals("en", store().load().targetLanguage)
        assertEquals("es", store().bookSource("book-a"))
    }
}

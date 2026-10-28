package com.nexo.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10: persisted personal preferences are per-account.
 *
 * Proves the two-account isolation the task requires (reader prefs and the
 * reading goal), plus the decision-D5 rule that the anonymous path reads
 * defaults and writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountPreferencesIsolationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    // ── Reader prefs ────────────────────────────────────────────────

    @Test
    fun `reader prefs are independent per account`() {
        val accountA = ReaderPreferences(context, providerFor(TEST_USER_ID))
        val accountB = ReaderPreferences(context, providerFor(OTHER_USER_ID))

        accountA.save(ReaderSettings(fontSize = FontSizePreset.XL, theme = ReaderTheme.LIGHT))
        accountB.save(ReaderSettings(fontSize = FontSizePreset.S, theme = ReaderTheme.DARK))

        val aReloaded = ReaderPreferences(context, providerFor(TEST_USER_ID))
        val bReloaded = ReaderPreferences(context, providerFor(OTHER_USER_ID))
        assertEquals(FontSizePreset.XL, aReloaded.load().fontSize)
        assertEquals(ReaderTheme.LIGHT, aReloaded.load().theme)
        assertEquals(FontSizePreset.S, bReloaded.load().fontSize)
        assertEquals(ReaderTheme.DARK, bReloaded.load().theme)
        assertNotEquals(aReloaded.load().fontSize, bReloaded.load().fontSize)
    }

    @Test
    fun `reader prefs write nothing on the anonymous path and read defaults`() {
        val anonymous = ReaderPreferences(context, providerFor("local-abc"))

        anonymous.save(ReaderSettings(fontSize = FontSizePreset.XL))

        val afterSignIn = ReaderPreferences(context, providerFor(TEST_USER_ID))
        assertEquals(ReaderSettings().fontSize, afterSignIn.load().fontSize)
        // No owner file was created for the throwaway id.
        assertNull(AccountPreferences.fileNameFor(ReaderPreferences.PREFS_NAME, providerFor("local-x")))
    }

    @Test
    fun `reader prefs read defaults when signed out`() {
        assertEquals(ReaderSettings(), ReaderPreferences(context, providerFor(null)).load())
    }

    // ── Reading goal (also the onboarding gate input) ───────────────

    @Test
    fun `reading goal is independent per account`() {
        ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).save(45)

        assertEquals(45, ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).load())
        // Account B has no goal: it must still see onboarding.
        assertNull(ReadingGoalPreferences(context, providerFor(OTHER_USER_ID)).load())
    }

    @Test
    fun `reading goal write is a no-op on the anonymous path`() {
        ReadingGoalPreferences(context, providerFor("local-abc")).save(45)

        assertNull(ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).load())
    }

    // ── Notification prefs ──────────────────────────────────────────

    @Test
    fun `notification prefs are independent per account`() {
        val accountA = NotificationPreferencesStore(context, providerFor(TEST_USER_ID))
        val accountB = NotificationPreferencesStore(context, providerFor(OTHER_USER_ID))

        accountA.save(NotificationPreferences.DEFAULT.copy(nudge = false))

        assertEquals(false, accountA.load().nudge)
        // B never wrote anything: it must read the defaults, not A's value.
        assertEquals(NotificationPreferences.DEFAULT.nudge, accountB.load().nudge)
    }
}

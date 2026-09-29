package com.nextpage.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nextpage.data.session.UpdatePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatePrefsTest {
    private fun prefs(): UpdatePrefs {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return UpdatePrefs(context).apply { clear() }
    }

    @Test
    fun remindLater_suppressesOnlyDismissedVersion() {
        val prefs = prefs()

        prefs.remindLater("0.4.0", 1_000L)

        assertTrue(prefs.isSuppressed("0.4.0", 2_000L))
        assertFalse(prefs.isSuppressed("0.5.0", 2_000L))
    }

    @Test
    fun suppression_expiresAfterInterval() {
        val prefs = prefs()

        prefs.remindLater("0.4.0", 1_000L)

        assertFalse(prefs.isSuppressed("0.4.0", 1_000L + UpdatePrefs.REMIND_LATER_INTERVAL_MS))
    }

    @Test
    fun suppression_survivesRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UpdatePrefs(context).apply {
            clear()
            remindLater("0.4.0", 5_000L)
        }

        val reloaded = UpdatePrefs(context)

        assertTrue(reloaded.isSuppressed("0.4.0", 6_000L))
    }
}

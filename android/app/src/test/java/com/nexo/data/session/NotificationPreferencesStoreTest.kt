package com.nexo.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.notification.QuietHoursSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U9: the preferences store round-trips through SharedPreferences and degrades to defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPreferencesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `absent preferences read as defaults`() {
        val store = NotificationPreferencesStore(context)
        assertEquals(NotificationPreferences.DEFAULT, store.load())
    }

    @Test
    fun `write then read returns the same values`() {
        val store = NotificationPreferencesStore(context)
        val preferences =
            NotificationPreferences(
                system = false,
                nudge = true,
                quietHours = QuietHoursSchedule(enabled = true, start = "21:30", end = "06:45"),
            )
        store.save(preferences)

        assertEquals(preferences, NotificationPreferencesStore(context).load())
    }

    @Test
    fun `corrupted blob degrades to defaults`() {
        context
            .getSharedPreferences("nexo_notification_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("preferences", "{ this is not json")
            .commit()

        assertEquals(NotificationPreferences.DEFAULT, NotificationPreferencesStore(context).load())
    }
}

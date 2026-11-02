package com.nexo.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4: the notification opt-in bookkeeping.
 *
 * The behaviour that must hold regardless of how the screen looks: the request
 * is one-shot, a skip/denial is recorded as "asked", and a denied install is
 * reported as blocked so the screen offers system settings instead of a dialog
 * the OS will silently swallow (Android 13+).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        context
            .getSharedPreferences("nexo_notification_permission", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `fresh install has never asked`() {
        assertFalse(NotificationPermissionPrefs.wasRequested(context))
        assertFalse(NotificationPermissionPrefs.wasDenied(context))
    }

    @Test
    fun `markRequested makes it one-shot`() {
        NotificationPermissionPrefs.markRequested(context)
        assertTrue(NotificationPermissionPrefs.wasRequested(context))
    }

    @Test
    fun `denial is recorded separately from the request flag`() {
        NotificationPermissionPrefs.markRequested(context)
        NotificationPermissionPrefs.markDenied(context)

        assertTrue(NotificationPermissionPrefs.wasRequested(context))
        assertTrue(NotificationPermissionPrefs.wasDenied(context))
    }

    @Test
    fun `never asked is not blocked`() {
        // No request yet: the dialog is still available, so not blocked.
        assertFalse(NotificationPermissionPrefs.isBlocked(context))
    }

    @Test
    fun `permission is required on api 33 plus`() {
        // Config pins SDK 34, so the runtime permission exists.
        assertTrue(NotificationPermissionPrefs.isRuntimePermissionRequired())
    }
}

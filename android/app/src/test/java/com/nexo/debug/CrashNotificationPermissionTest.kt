package com.nexo.debug

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.nexo.NexoApplication
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The debug crash notification must not reach `NotificationManagerCompat.notify`
 * when POST_NOTIFICATIONS is not granted.
 *
 * The call used to sit unguarded inside a `runCatching`, so on API 33+ a denial
 * threw `SecurityException` and was swallowed: the user never saw the crash
 * notification and nothing recorded why. The gate is `BuildConfig.DEBUG`-only
 * code, so the observable proof is the OS tray: a denied run must leave the
 * posted-notification count untouched, and must not consume the persisted crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashNotificationPermissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        seedCrash(null)
    }

    @Test
    fun `denied POST_NOTIFICATIONS does not attempt notify`() {
        assumePostNotificationsRuntimePermission()
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seedCrash(CRASH_JSON)

        CrashNotificationHelper.showCrashNotificationIfAny(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(
            "no notification may be posted without POST_NOTIFICATIONS",
            0,
            shadowOf(manager).size(),
        )
        assertNotNull(
            "a denied run must keep the crash so the next launch can retry it",
            pendingCrash(),
        )
    }

    @Test
    fun `granted POST_NOTIFICATIONS still posts the crash notification`() {
        assumePostNotificationsRuntimePermission()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seedCrash(CRASH_JSON)

        CrashNotificationHelper.showCrashNotificationIfAny(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, shadowOf(manager).size())
        assertFalse(
            "a delivered crash notification must be cleared so it shows once",
            pendingCrash().let { it != null && it.isNotEmpty() },
        )
    }

    /** Robolectric shadows the permission as declared but not granted per-app. */
    private fun assumePostNotificationsRuntimePermission() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    private fun seedCrash(raw: String?) {
        val prefs =
            context.getSharedPreferences(NexoApplication.PREFS_NAME, Context.MODE_PRIVATE)
        if (raw == null) {
            prefs.edit().remove(NexoApplication.KEY_LAST_CRASH).apply()
        } else {
            prefs.edit().putString(NexoApplication.KEY_LAST_CRASH, raw).apply()
        }
    }

    private fun pendingCrash(): String? =
        context
            .getSharedPreferences(NexoApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(NexoApplication.KEY_LAST_CRASH, null)

    private companion object {
        val CRASH_JSON = JSONObject().apply { put("message", "boom") }.toString()
    }
}

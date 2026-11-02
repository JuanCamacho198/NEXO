package com.nexo.data.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * One-shot record of whether `POST_NOTIFICATIONS` has already been requested,
 * and whether the user's answer was a denial.
 *
 * The permission is requested **after onboarding** (see
 * [com.nexo.presentation.screen.OnboardingNotificationsScreen]): the user is
 * shown what notifications buy them before the OS dialog. A denied permission is
 * silently returned by a second request on Android 13+, so re-prompting every
 * launch is pure nagging — [wasRequested] makes the request exactly-once per
 * install, and it is set BEFORE the launcher fires so a dismissal without an
 * answer also counts as "asked".
 *
 * [markDenied] additionally records a **denial** so the opt-in screen can offer
 * "open system settings" instead of a request that the OS will swallow. It is
 * not a second prompt mechanism: the request still happens at most once.
 *
 * ## Why this is a singleton object and not injected
 *
 * It reads only [android.content.SharedPreferences] and the platform permission
 * check, and both the Activity (the launcher) and the Compose screen (the
 * button) need it in the same process. Injecting it would add a container
 * dependency for two booleans.
 */
object NotificationPermissionPrefs {
    private const val PREFS_NAME = "nexo_notification_permission"
    private const val KEY_REQUESTED = "post_notifications_requested"
    private const val KEY_DENIED = "post_notifications_denied"

    fun wasRequested(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    /** True when the last request was denied (as opposed to never asked). */
    fun wasDenied(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DENIED, false)

    fun markDenied(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DENIED, true)
            .apply()
    }

    /**
     * True when the OS will not show the permission dialog again for this
     * install: the permission is not granted AND we already asked.
     *
     * On Android 13+ a denied permission is never re-presented by a second
     * request (the call returns denied without UI), so in that state the only
     * path left is the system settings screen. Below API 33 the permission does
     * not exist and this is always false.
     */
    fun isBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return !granted && (wasDenied(context) || wasRequested(context))
    }

    /** True when the permission is currently granted. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True on API 33+, where the runtime permission exists at all. */
    fun isRuntimePermissionRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

package com.nexo.data.session

import android.content.Context

/**
 * One-shot record of whether `POST_NOTIFICATIONS` has already been requested.
 *
 * The permission is requested on the real startup path (API 33+) so the OS
 * notification surface can work for everyone, not just debug builds. On Android
 * a denied permission is silently returned by a second request, so re-prompting
 * every launch is pure nagging — the flag makes the request exactly-once per
 * install. It is set BEFORE the launcher fires, so a dismissal without an answer
 * also counts as "asked".
 */
object NotificationPermissionPrefs {
    private const val PREFS_NAME = "nexo_notification_permission"
    private const val KEY_REQUESTED = "post_notifications_requested"

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
}

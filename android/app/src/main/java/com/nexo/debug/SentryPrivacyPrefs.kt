package com.nexo.debug

import android.content.Context

/**
 * PP-3 runtime telemetry opt-out. The Android DSN is compile-time, so this
 * flag is consulted in `beforeSend`/`beforeBreadcrumb` (veto → stop SENDING)
 * rather than shutting the SDK down. Default is enabled; persists across
 * launches.
 */
object SentryPrivacyPrefs {
    private const val PREFS_NAME = "nexo_sentry"
    private const val KEY_ENABLED = "telemetry_enabled"

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

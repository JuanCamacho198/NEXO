package com.nexo.data.session

import android.content.Context

/**
 * Durable one-time legal disclaimer flag (U5).
 *
 * Mirrors the [DriveConnectPromptPrefs] SharedPreferences pattern — plain
 * `getSharedPreferences`, no encryption needed (a boolean marker, not a
 * secret). Survives process restart: once [accept] is recorded,
 * [hasAccepted] stays true across restarts so [LegalNoticeDialog] shows
 * exactly once per install.
 */
class LegalDisclaimerPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once the user accepted the one-time legal disclaimer. */
    fun hasAccepted(): Boolean = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    /** Persist disclaimer acceptance durably (one-time; never re-prompted). */
    fun accept() {
        prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "legal_disclaimer"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    }
}

package com.nexo.data.session

import android.content.Context

/**
 * Per-account persistence for the one-time Drive connect prompt decision.
 *
 * Only the DECLINE is persisted, keyed to the Google account that made it:
 * accepting leaves the prompt gated by Drive's authorized state (nothing to
 * store), and switching accounts gets a fresh offer because the marker is
 * compared against the current user's id. [clearDeclined] resets the marker
 * so a later import MAY re-offer (spec: "MAY re-appear once Drive is disabled
 * in Settings").
 *
 * Mirrors the [ReaderPreferences] SharedPreferences pattern — plain
 * `getSharedPreferences`, no encryption needed (a per-account boolean marker,
 * not a secret).
 */
class DriveConnectPromptPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The userId that declined the last prompt, or null when never declined / cleared. */
    fun declinedForUser(): String? = prefs.getString(KEY_DECLINED_FOR_USER, null)

    /** Persist a decline for [userId] so the prompt never re-appears for that account. */
    fun persistDeclined(userId: String) {
        prefs.edit().putString(KEY_DECLINED_FOR_USER, userId).apply()
    }

    /** Clear the decline marker (Drive re-authorized, account changed, etc.). */
    fun clearDeclined() {
        prefs.edit().remove(KEY_DECLINED_FOR_USER).apply()
    }

    companion object {
        private const val PREFS_NAME = "drive_connect_prompt"
        private const val KEY_DECLINED_FOR_USER = "declined_for_user"
    }
}

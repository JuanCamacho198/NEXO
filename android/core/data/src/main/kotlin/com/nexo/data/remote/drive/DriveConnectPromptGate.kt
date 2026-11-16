package com.nexo.data.remote.drive

/**
 * Pure, JVM-testable decision gate for the one-time "Connect Google Drive?"
 * prompt shown after a successful book import. No Android dependency — every
 * input is passed in, so this can be unit-tested without Compose or a device.
 *
 * Decision (spec `drive-import-connect`): show the prompt only when ALL hold —
 * the import succeeded, Drive is NOT yet authorized, the user is signed in with
 * Google, and this account has not previously declined. Accepting persists
 * nothing here: Drive becoming authorized gates future prompts, and a later
 * Settings disconnect MAY re-offer (the decline marker is cleared on a
 * successful authorization — see the NavHost wiring).
 */
object DriveConnectPromptGate {
    /**
     * Whether the one-time connect prompt should appear for the current state.
     *
     * @param importSucceeded True only for a [com.nexo.presentation.viewmodel.LibraryImportEvent.Success].
     * @param driveEnabled True when Drive tokens are already authorized (then Drive is usable — no prompt).
     * @param providerIsGoogle True when the signed-in account's provider is "google"
     *   (Supabase `user_metadata.provider`). Email/anonymous/local accounts are never offered Drive.
     * @param declinedForUser The userId persisted by a previous decline, or null.
     * @param currentUser The signed-in user's id, or null when not signed in.
     */
    fun shouldShow(
        importSucceeded: Boolean,
        driveEnabled: Boolean,
        providerIsGoogle: Boolean,
        declinedForUser: String?,
        currentUser: String?,
    ): Boolean {
        if (!importSucceeded || driveEnabled || !providerIsGoogle || currentUser.isNullOrBlank()) {
            return false
        }
        return declinedForUser != currentUser
    }

    /**
     * Compute the persisted marker for a decline by [currentUser]. Pure: returns
     * the value the caller must store via [com.nexo.data.session.DriveConnectPromptPrefs]
     * so this account is not re-offered. Null when there is no signed-in user to
     * attribute the decline to (nothing to persist).
     */
    fun markDeclined(currentUser: String?): String? = currentUser?.takeIf { it.isNotBlank() }
}

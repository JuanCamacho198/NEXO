package com.nexo.data.remote.addons

import android.content.Context

/**
 * Durable [AddonConsentStore] (U5 — delivers the consent persistence
 * deferred from U4).
 *
 * U4 kept consent in [InMemoryAddonConsentStore], so a recorded disclosure
 * consent was lost on process restart and the resolve gate closed again.
 * This store persists the same set semantics (record-once, idempotent,
 * revocable) in SharedPreferences, mirroring the [DriveConnectPromptPrefs]
 * pattern: a per-addon boolean marker keyed by addon id, no encryption
 * needed (consent markers, not secrets).
 *
 * Thread-safety: `SharedPreferences.edit().apply()` is atomic per write;
 * reads go through the in-memory cache, so this is safe to call from any
 * dispatcher, including the providers' I/O threads.
 */
class PersistentAddonConsentStore(
    context: Context,
) : AddonConsentStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun hasConsent(addonId: String): Boolean = prefs.getBoolean(consentKey(addonId), false)

    @Synchronized
    override fun recordConsent(addonId: String) {
        prefs.edit().putBoolean(consentKey(addonId), true).apply()
    }

    @Synchronized
    override fun revokeConsent(addonId: String) {
        prefs.edit().remove(consentKey(addonId)).apply()
    }

    companion object {
        private const val PREFS_NAME = "addon_consent"
        private const val KEY_PREFIX_CONSENT = "consented:"

        private fun consentKey(addonId: String): String = KEY_PREFIX_CONSENT + addonId
    }
}

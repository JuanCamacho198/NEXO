package com.nexo.data.remote.addons

/**
 * U4 registry-level consent store: per-addon capability-disclosure consent.
 *
 * No addon-specific preferences mechanism exists in the repo (grep:
 * SharedPreferences holders cover reader/theme/language/goals/drive-prompt
 * only), so no new storage mechanism is invented here — consent lives in
 * this registry-level store, recorded once per addon (set semantics:
 * re-recording is a no-op). U5 (consent/legal UI + its DataStore
 * disclaimer flag) persists the decision durably when it builds the
 * disclosure surface; until then the in-memory default keeps the gate
 * fail-closed (no consent ⇒ no resolve, no network).
 */
interface AddonConsentStore {
    fun hasConsent(addonId: String): Boolean

    /** Record disclosure consent for [addonId]; idempotent (recorded once). */
    fun recordConsent(addonId: String)

    fun revokeConsent(addonId: String)
}

/** Process-local [AddonConsentStore]: consent recorded once per addon id. */
class InMemoryAddonConsentStore : AddonConsentStore {
    private val consentedIds = mutableSetOf<String>()

    @Synchronized
    override fun hasConsent(addonId: String): Boolean = addonId in consentedIds

    @Synchronized
    override fun recordConsent(addonId: String) {
        consentedIds.add(addonId)
    }

    @Synchronized
    override fun revokeConsent(addonId: String) {
        consentedIds.remove(addonId)
    }
}

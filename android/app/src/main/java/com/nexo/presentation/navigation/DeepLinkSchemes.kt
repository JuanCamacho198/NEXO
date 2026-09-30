package com.nexo.presentation.navigation

/**
 * Deep-link scheme policy for the app's own URIs (auth callbacks and addon
 * install links).
 *
 * [CANONICAL] is the NEXO scheme. [LEGACY] is RETAINED for the transition
 * window: password-reset and email-confirmation mails already delivered to
 * users, and previously shared addon links, must keep resolving. Both are
 * registered in `AndroidManifest.xml` and both reach the same handler.
 *
 * Retiring the legacy scheme is coupled to the Supabase redirect allowlist (a
 * project setting, not a repository file), so it happens in a later release,
 * never in this one.
 */
object DeepLinkSchemes {
    /** Canonical NEXO scheme. */
    const val CANONICAL = "nexo"

    /** Legacy scheme, kept working during the transition. */
    const val LEGACY = "nextpage"

    /**
     * Scheme supabase-kt is configured with (see `SupabaseClientProvider`).
     *
     * It deliberately stays on the legacy value: supabase-kt compares the
     * incoming intent against `AuthConfig.scheme` with strict equality, and the
     * same value also feeds `AuthConfig.deepLink`, the default auth redirect
     * URL — which must keep matching an entry in the Supabase redirect
     * allowlist. `nexo://` is accepted at the activity boundary and normalised
     * to this value, so both schemes are handled identically.
     */
    const val AUTH = LEGACY

    /** Schemes accepted for the app's own deep links; both map to one handler. */
    val accepted: Set<String> = setOf(CANONICAL, LEGACY)
}

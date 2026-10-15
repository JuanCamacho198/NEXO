package com.nexo.data.remote.supabase

import com.nexo.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json
import io.github.jan.supabase.auth.SessionManager as SupabaseAuthSessionManager

/**
 * Singleton factory that provides the Supabase client for Android.
 *
 * Uses supabase-kt v3 with Auth (auth), Postgrest (DB), and Realtime (live changes).
 * The client is session-aware: once the user signs in, all requests carry the
 * auth token via Auth (RLS applies automatically).
 *
 * @see [SupabaseDeviceDataSource] for direct DB access using this client.
 */
object SupabaseClientProvider {
    private var _client: SupabaseClient? = null

    @Volatile
    private var sessionManager: SupabaseAuthSessionManager? = null

    /**
     * Installs the session store supabase-kt persists tokens through.
     *
     * The app wires the encrypted Keystore-backed store here (0.3.5 secrets encryption); without it the client
     * falls back to supabase-kt's plaintext default. Must be called before the first [client] access — enforced,
     * because silently keeping plaintext after opting into encryption would be worse than crashing.
     */
    fun configureSessionManager(manager: SupabaseAuthSessionManager) {
        check(_client == null) { "SupabaseClientProvider already initialized" }
        sessionManager = manager
    }

    /**
     * The session-aware Supabase client. Created lazily on first access.
     * Uses OkHttp engine (Ktor) for WebSocket support (Realtime).
     */
    val client: SupabaseClient
        get() {
            var client = _client
            if (client == null) {
                client = createClient()
                _client = client
            }
            return client
        }

    private fun createClient(): SupabaseClient {
        val url = BuildConfig.SUPABASE_URL
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        require(url.isNotBlank()) { "SUPABASE_URL is not configured" }
        require(anonKey.isNotBlank()) { "SUPABASE_ANON_KEY is not configured" }

        return createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = anonKey,
        ) {
            install(Auth) {
                // Deep-link scheme/host that supabase-kt matches incoming auth
                // intents against (OAuth callback, password reset, email
                // confirmation). `nextpage` is the LEGACY scheme and is kept here
                // on purpose — supabase-kt compares with strict equality and the
                // same value feeds `AuthConfig.deepLink`, the default auth
                // redirect URL, which must keep matching the Supabase redirect
                // allowlist (a project setting). MainActivity normalises an
                // incoming `nexo://auth/...` to this scheme, so both schemes
                // registered in AndroidManifest.xml reach the same handler.
                scheme = "nextpage"
                host = "auth"
                // Encrypted token storage (0.3.5). Installed when the app
                // configured one before first client access; otherwise the
                // supabase-kt plaintext default applies (tests, misconfig).
                this@SupabaseClientProvider.sessionManager?.let { sessionManager = it }
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
            // Coerce `null` from the DB into each field's default instead of
            // throwing. Postgres columns are nullable while their Kotlin models
            // declare non-null types with defaults (e.g. reading_progress
            // `percentage: Double = 0.0`), so a single NULL row would otherwise
            // crash Realtime deserialization — see NEXO-ANDROID-P/Q.
            defaultSerializer =
                KotlinXSerializer(
                    json =
                        Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        },
                )
        }
    }

    /**
     * Reset the client (used after sign-out).
     */
    fun reset() {
        _client = null
    }
}

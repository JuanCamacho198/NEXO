package com.nexo.data.remote.supabase

import com.nexo.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

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
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    /**
     * Reset the client (used after sign-out).
     */
    fun reset() {
        _client = null
    }
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.nexo.data.session

import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.debug.DebugLog
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a string value from Supabase user metadata.
 *
 * `UserInfo.userMetadata` is a `JsonObject` whose values are [JsonElement]s,
 * NOT Kotlin [String]s — the naive `as? String` cast always yields null, which
 * silently drops `full_name`/`avatar_url` and leaves the UI on fallback
 * ("Reader" + initial avatar) even though Supabase has the data.
 */
internal fun JsonElement?.asMetadataString(): String? = this?.jsonPrimitive?.contentOrNull

/**
 * SessionManager backed by Supabase Auth.
 *
 * The session is managed natively by supabase-kt (persisted in app storage,
 * auto-refreshed). This adapter maps to the existing [SessionManager] interface
 * so consuming code (SyncService, etc.) does not need refactoring.
 *
 * @see SessionManager
 * @see SupabaseClientProvider
 */
class SupabaseSessionManager(
    private val sessionStore: SessionStore = InMemorySessionStore(),
) : SessionManager {
    private val supabase get() = SupabaseClientProvider.client

    override suspend fun restoreSession(): Result<AuthSession?> = getCurrentSession()

    /**
     * Waits for supabase-kt to finish loading the persisted session before reading it.
     *
     * `Auth.init()` restores the session from storage on `authScope` **in the
     * background**, so `sessionStatus` is still `Initializing` for the first
     * milliseconds of a cold start. Reading `currentSessionOrNull()` without
     * awaiting returns `null` during that window, which is indistinguishable
     * from "signed out" — the app would route to the login screen even though a
     * valid session is on disk. That is a race, so it only reproduces sometimes.
     */
    private suspend fun awaitAuthInitialized() {
        runCatching { supabase.auth.awaitInitialization() }
            .onFailure { DebugLog.warn(TAG, "awaitInitialization failed: ${it.message}") }
    }

    override suspend fun getCurrentSession(): Result<AuthSession?> =
        try {
            awaitAuthInitialized()
            val session = supabase.auth.currentSessionOrNull()
            val authSession = session?.toAuthSession()
            DebugLog.info(
                TAG,
                "getCurrentSession: ${if (authSession == null) "no session" else "session restored for ${authSession.userId}"}",
            )
            Result.success(authSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.warn(TAG, "getCurrentSession failed: ${e.message}")
            Result.failure(e)
        }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override suspend fun ensureFreshSession(): Result<AuthSession> {
        awaitAuthInitialized()
        val current = supabase.auth.currentSessionOrNull()
        if (current == null) {
            DebugLog.warn(TAG, "ensureFreshSession: no session available")
            return Result.failure(missingSessionError())
        }

        val expiresSoon = current.expiresAt.epochSeconds <= System.currentTimeMillis() / 1000 + REFRESH_WINDOW_SECONDS
        if (!expiresSoon) {
            DebugLog.info(TAG, "ensureFreshSession: token still valid, no refresh needed")
            return current
                .toAuthSession()
                ?.let { Result.success(it) }
                ?: Result.failure(missingSessionError())
        }

        return try {
            DebugLog.info(TAG, "ensureFreshSession: token expires soon, refreshing")
            supabase.auth.refreshCurrentSession()
            val refreshed = supabase.auth.currentSessionOrNull()
            val authSession = refreshed?.toAuthSession()
            if (authSession == null) {
                // Refresh reported success but left no session: fall back to what
                // was on disk rather than inventing a signed-out state.
                DebugLog.warn(TAG, "ensureFreshSession: refresh left no session, falling back to persisted session")
                current
                    .toAuthSession()
                    ?.let { Result.success(it) }
                    ?: Result.failure(missingSessionError())
            } else {
                DebugLog.info(TAG, "ensureFreshSession: refreshed for ${authSession.userId}")
                Result.success(authSession)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transient refresh failure (network down, Play/SDK hiccup, 5xx)
            // does NOT mean the user has no session. Keep serving the persisted
            // session so callers do not interpret this as "signed out" — an
            // unexpired token is still usable while the retry window runs.
            DebugLog.warn(
                TAG,
                "ensureFreshSession: refresh failed (${e.javaClass.simpleName}: ${e.message}); " +
                    "serving persisted session instead of failing",
            )
            current
                .toAuthSession()
                ?.let { Result.success(it) }
                ?: Result.failure(e)
        }
    }

    private fun UserSession.toAuthSession(): AuthSession? =
        user?.let { user ->
            AuthSession(
                userId = user.id,
                email = user.email,
                displayName =
                    user.userMetadata?.get("full_name").asMetadataString()
                        ?: user.userMetadata?.get("name").asMetadataString(),
                photoUrl =
                    user.userMetadata?.get("avatar_url").asMetadataString()
                        ?: user.userMetadata?.get("picture").asMetadataString(),
                providerToken = providerToken,
                provider = user.userMetadata?.get("provider").asMetadataString(),
                createdAt = user.createdAt?.toString(),
            )
        }

    private fun missingSessionError(): AppError =
        AppError(
            category = ErrorCategory.AUTH,
            code = "SUPABASE_SESSION_MISSING",
            message = "No session available.",
            component = COMPONENT,
        )

    override suspend fun signOutAll(): Result<Unit> =
        try {
            supabase.auth.signOut()
            SupabaseClientProvider.reset()
            runCatching { sessionStore.clear() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Persists the [AuthSession] metadata mirror into the encrypted [SessionStore] (0.3.5 secrets encryption).
     * supabase-kt keeps owning the token pair in its own encrypted store; this keeps the UI/domain mirror that
     * repositories (Google, Supabase, anonymous) publish on every sign-in next to it instead of dropping it.
     */
    override suspend fun setCurrentSession(session: AuthSession?): Result<Unit> =
        runCatching {
            if (session == null) {
                sessionStore.clear()
            } else {
                sessionStore.write(session)
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { throwable ->
                Result.failure(
                    AppError(
                        category = ErrorCategory.WIRING_ERROR,
                        code = "SUPABASE_SESSION_PERSIST_FAILED",
                        message = throwable.message ?: "Failed to persist local session.",
                        component = COMPONENT,
                    ),
                )
            },
        )

    companion object {
        const val COMPONENT = "SupabaseSessionManager"

        private const val TAG = COMPONENT

        /** Refresh an access token this many seconds before it actually expires. */
        private const val REFRESH_WINDOW_SECONDS = 60L
    }
}

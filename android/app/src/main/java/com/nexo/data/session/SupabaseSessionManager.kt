@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.nexo.data.session

import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import io.github.jan.supabase.auth.auth
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

    override suspend fun getCurrentSession(): Result<AuthSession?> =
        try {
            val session = supabase.auth.currentSessionOrNull()
            val authSession =
                session?.let { s ->
                    s.user?.let { user ->
                        AuthSession(
                            userId = user.id,
                            email = user.email,
                            displayName =
                                user.userMetadata?.get("full_name").asMetadataString()
                                    ?: user.userMetadata?.get("name").asMetadataString(),
                            photoUrl =
                                user.userMetadata?.get("avatar_url").asMetadataString()
                                    ?: user.userMetadata?.get("picture").asMetadataString(),
                            providerToken = s.providerToken,
                            provider = user.userMetadata?.get("provider").asMetadataString(),
                            createdAt = user.createdAt?.toString(),
                        )
                    }
                }
            Result.success(authSession)
        } catch (e: Exception) {
            Result.failure(e)
        }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override suspend fun ensureFreshSession(): Result<AuthSession> {
        return try {
            val session = supabase.auth.currentSessionOrNull()
            if (session == null || session.expiresAt.epochSeconds <= System.currentTimeMillis() / 1000 + 60) {
                supabase.auth.refreshCurrentSession()
                val freshSession =
                    supabase.auth.currentSessionOrNull()
                        ?: return Result.failure(Exception("No session after refresh"))
                val authSession =
                    freshSession.user?.let { user ->
                        AuthSession(
                            userId = user.id,
                            email = user.email,
                            displayName =
                                user.userMetadata?.get("full_name").asMetadataString()
                                    ?: user.userMetadata?.get("name").asMetadataString(),
                            photoUrl =
                                user.userMetadata?.get("avatar_url").asMetadataString()
                                    ?: user.userMetadata?.get("picture").asMetadataString(),
                            providerToken = freshSession.providerToken,
                            provider = user.userMetadata?.get("provider").asMetadataString(),
                            createdAt = user.createdAt?.toString(),
                        )
                    } ?: return Result.failure(Exception("No user in session after refresh"))
                Result.success(authSession)
            } else {
                getCurrentSession().mapCatching { requireNotNull(it) { "No session after refresh" } }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
    }
}

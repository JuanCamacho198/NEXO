// Legacy backward-compat implementation, kept until the Supabase migration is
// complete. Uses the deprecated Google Sign-In API by design.
@file:Suppress("DEPRECATION")

package com.nexo.data.session

import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession

/**
 * @deprecated Replaced by [SupabaseSessionManager].
 * Will be removed in the next release cycle.
 */

/**
 * SessionManager implementation backed by Google Sign-In OAuth token lifecycle.
 *
 * Stores session metadata via [SessionStore]. Token refresh uses
 * [GoogleSignInClient.silentSignIn] — the official Android method for obtaining
 * a fresh OAuth access token without user interaction.
 */
@Deprecated("Replaced by SupabaseSessionManager", ReplaceWith("SupabaseSessionManager"))
class GoogleSessionManager(
    private val googleSignInClient: GoogleSignInClient?,
    private val diagnosticError: AppError?,
    private val sessionStore: SessionStore,
    private val isClientAvailable: Boolean = googleSignInClient != null,
) : SessionManager {
    @Volatile
    private var currentSession: AuthSession? = null

    override suspend fun restoreSession(): Result<AuthSession?> {
        val cached = currentSession
        if (cached != null) {
            return Result.success(cached)
        }

        return runCatching { sessionStore.read() }
            .fold(
                onSuccess = { restored ->
                    currentSession = restored
                    Result.success(restored)
                },
                onFailure = { throwable ->
                    Result.failure(
                        AppError(
                            category = ErrorCategory.WIRING_ERROR,
                            code = "GOOGLE_SESSION_RESTORE_FAILED",
                            message = throwable.message ?: "Failed to restore persisted session.",
                            component = COMPONENT,
                        ),
                    )
                },
            )
    }

    override suspend fun getCurrentSession(): Result<AuthSession?> {
        val current = currentSession
        return if (current != null) {
            Result.success(current)
        } else {
            restoreSession()
        }
    }

    override suspend fun ensureFreshSession(): Result<AuthSession> {
        if (!isClientAvailable) {
            return Result.failure(missingClientError())
        }

        val client = googleSignInClient ?: return Result.failure(missingClientError())

        val signInResult =
            runCatching {
                val task = client.silentSignIn()
                Tasks.await(task)
            }

        return signInResult.fold(
            onSuccess = { account ->
                val session =
                    AuthSession(
                        userId = account?.id ?: "google-user",
                        email = account?.email,
                        displayName = account?.displayName,
                        photoUrl = account?.photoUrl?.toString(),
                    )
                currentSession = session
                runCatching { sessionStore.write(session) }
                Result.success(session)
            },
            onFailure = { error ->
                // If silentSignIn fails, we may still have a stored session
                val existing = currentSession ?: runCatching { sessionStore.read() }.getOrNull()
                if (existing != null) {
                    return Result.success(existing)
                }
                val apiError = error as? ApiException
                val code =
                    when (apiError?.statusCode) {
                        12501 -> "GOOGLE_SESSION_DISABLED" // SIGN_IN_CURRENTLY_IN_PROGRESS
                        12500 -> "GOOGLE_SESSION_CANCELLED"
                        else -> "GOOGLE_SESSION_REFRESH_FAILED"
                    }
                Result.failure(
                    AppError(
                        category = ErrorCategory.WIRING_ERROR,
                        code = code,
                        message = error.message ?: "Failed to refresh Google session.",
                        component = COMPONENT,
                    ),
                )
            },
        )
    }

    override suspend fun signOutAll(): Result<Unit> {
        currentSession = null
        runCatching { sessionStore.clear() }

        val client = googleSignInClient
        if (isClientAvailable && client != null) {
            runCatching {
                val task = client.signOut()
                Tasks.await(task)
            }
        }

        return Result.success(Unit)
    }

    override suspend fun setCurrentSession(session: AuthSession?): Result<Unit> {
        currentSession = session
        return runCatching {
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
                        code = "GOOGLE_SESSION_PERSIST_FAILED",
                        message = throwable.message ?: "Failed to persist local session.",
                        component = COMPONENT,
                    ),
                )
            },
        )
    }

    private fun missingClientError(): AppError =
        diagnosticError ?: AppError(
            category = ErrorCategory.WIRING_ERROR,
            code = "GOOGLE_SESSION_CLIENT_NOT_AVAILABLE",
            message = "Google Sign-In client is not available in session manager.",
            component = COMPONENT,
        )

    companion object {
        const val COMPONENT = "GoogleSessionManager"
    }
}

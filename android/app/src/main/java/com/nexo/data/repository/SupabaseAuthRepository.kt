@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.nexo.data.repository

import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.data.session.SessionManager
import com.nexo.data.session.SupabaseSessionManager
import com.nexo.data.session.asMetadataString
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import com.nexo.domain.repository.AuthRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AuthRepository backed by Supabase Auth.
 *
 * Replaces [GoogleAuthRepository] (kept as deprecated for 1 release cycle).
 *
 * Google sign-in flow:
 * 1. UI layer obtains a Google ID token via Credential Manager
 *    ([ActivityResultContracts.GetCredential]).
 * 2. The token is passed to [signInWithGoogleIdToken], which sends it to Supabase
 *    Auth via [IDToken] provider — no browser needed.
 * 3. Session is persisted automatically by supabase-kt's Auth plugin.
 */
class SupabaseAuthRepository(
    private val sessionManager: SessionManager = SupabaseSessionManager(),
) : AuthRepository {
    private val supabase get() = SupabaseClientProvider.client

    override suspend fun startGoogleSignIn(): Result<String> = Result.failure(UnsupportedOperationException("Deprecated. Use signInWithGoogleIdToken() instead."))

    override suspend fun completeGoogleSignIn(callbackUri: String): Result<AuthSession?> = Result.failure(UnsupportedOperationException("Deprecated. Use signInWithGoogleIdToken() instead."))

    override suspend fun signInWithGoogle(): Result<AuthSession> = Result.failure(UnsupportedOperationException("Deprecated. Use signInWithGoogleIdToken() instead."))

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthSession> =
        runCatching {
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
            }
            val session =
                supabase.auth.currentSessionOrNull()
                    ?: throw Exception("No session returned after Google sign-in")
            val authSession =
                mapToAuthSession(session.user)
                    ?: throw Exception("No user info returned after Google sign-in")
            sessionManager.setCurrentSession(authSession)
            authSession
        }

    override suspend fun signIn(
        email: String,
        password: String,
    ): Result<AuthSession> =
        runCatching {
            try {
                supabase.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            } catch (e: Exception) {
                throw friendlyAuthError(e, "Sign in failed. Please try again.")
            }
            val authSession =
                supabase.auth
                    .currentSessionOrNull()
                    ?.user
                    ?.let { mapToAuthSession(it) }
                    ?: throw Exception("No user info returned after sign-in")
            sessionManager.setCurrentSession(authSession)
            authSession
        }

    override suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
    ): Result<AuthSession> =
        runCatching {
            val prior = supabase.auth.currentSessionOrNull()
            try {
                supabase.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                    // Extra user metadata on signup; surfaced back via
                    // `userMetadata["full_name"]` (see mapToAuthSession).
                    data = buildJsonObject { put("full_name", fullName) }
                }
            } catch (e: Exception) {
                throw friendlyAuthError(e, "Sign up failed. Please try again.")
            }
            val fresh = supabase.auth.currentSessionOrNull()
            if (fresh == null) {
                // No session after signUpWith: the project has "Confirm email"
                // enabled, so the account was created but needs inbox
                // confirmation. That is a normal first step for a NEW address,
                // not a failure — surface it as an actionable message.
                if (prior == null) {
                    throw AppError(
                        category = ErrorCategory.AUTH,
                        code = SIGNUP_CONFIRMATION_PENDING_CODE,
                        message = SIGNUP_CONFIRMATION_PENDING_MESSAGE,
                        component = COMPONENT,
                    )
                }
                throw AppError(
                    category = ErrorCategory.AUTH,
                    code = SIGNUP_STALE_SESSION_CODE,
                    message = SIGNUP_UNIFIED_MESSAGE,
                    component = COMPONENT,
                )
            }
            if (!isSignUpNewSession(prior?.user?.id, fresh.user?.id)) {
                throw AppError(
                    category = ErrorCategory.AUTH,
                    code = SIGNUP_STALE_SESSION_CODE,
                    message = SIGNUP_UNIFIED_MESSAGE,
                    component = COMPONENT,
                )
            }
            val authSession =
                fresh.user?.let { mapToAuthSession(it) }
                    ?: throw Exception("No user info returned after sign-up")
            sessionManager.setCurrentSession(authSession)
            authSession
        }

    override suspend fun resetPassword(email: String): Result<Unit> =
        runCatching {
            try {
                supabase.auth.resetPasswordForEmail(email, redirectUrl = RESET_PASSWORD_REDIRECT_URL)
            } catch (e: Exception) {
                throw friendlyAuthError(e, "Failed to send reset email. Please try again.")
            }
        }

    override suspend fun signOut(): Result<Unit> =
        runCatching {
            supabase.auth.signOut()
            sessionManager.setCurrentSession(null)
            SupabaseClientProvider.reset()
            Unit
        }

    override suspend fun getCurrentSession(): Result<AuthSession?> =
        runCatching {
            val session = supabase.auth.currentSessionOrNull()
            session?.let { mapToAuthSession(it.user) }
        }

    override suspend fun signInLocally(): Result<AuthSession> =
        runCatching {
            supabase.auth.signInAnonymously()
            val session = supabase.auth.currentSessionOrNull()
            AuthSession(
                userId = session?.user?.id ?: "anon-${java.util.UUID.randomUUID()}",
                email = null,
                displayName = null,
                photoUrl = null,
            ).also { sessionManager.setCurrentSession(it) }
        }

    /**
     * Get the Google provider token from the current session.
     * Used by Google Drive sync operations.
     */
    suspend fun getProviderToken(): Result<String?> =
        runCatching {
            supabase.auth.currentSessionOrNull()?.providerToken
        }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Maps a raw GoTrue/Rest exception to a short, user-safe [AppError].
     *
     * The SDK exception message embeds the full HTTP request (URL, headers,
     * bearer token, API key) — it must NEVER be surfaced verbatim because the
     * auth screens render `errorMessage` inline. Known error codes get a
     * friendly phrase; anything else falls back to [fallback].
     */
    private fun friendlyAuthError(
        error: Throwable,
        fallback: String,
    ): AppError {
        val raw = error.message.orEmpty().lowercase()
        val code =
            when {
                "over_email_send_rate_limit" in raw || "rate limit" in raw -> "AUTH_EMAIL_RATE_LIMIT"
                "already registered" in raw || "user_already_exists" in raw -> "AUTH_EMAIL_ALREADY_REGISTERED"
                "invalid login credentials" in raw || "invalid_credentials" in raw -> "AUTH_INVALID_CREDENTIALS"
                "otp_expired" in raw || "link is invalid or has expired" in raw -> "AUTH_OTP_EXPIRED"
                else -> "AUTH_FAILED"
            }
        val message =
            when (code) {
                "AUTH_EMAIL_RATE_LIMIT" -> "Too many attempts — wait a moment and try again."
                "AUTH_EMAIL_ALREADY_REGISTERED" -> "This email is already registered — sign in instead."
                "AUTH_INVALID_CREDENTIALS" -> "Incorrect email or password."
                "AUTH_OTP_EXPIRED" -> "That link has expired. Request a new one."
                else -> fallback
            }
        return AppError(
            category = ErrorCategory.AUTH,
            code = code,
            message = message,
            component = COMPONENT,
        )
    }

    private fun mapToAuthSession(user: UserInfo?): AuthSession? =
        user?.let {
            AuthSession(
                userId = it.id,
                email = it.email,
                displayName =
                    it.userMetadata?.get("full_name").asMetadataString()
                        ?: it.userMetadata?.get("name").asMetadataString(),
                photoUrl =
                    it.userMetadata?.get("avatar_url").asMetadataString()
                        ?: it.userMetadata?.get("picture").asMetadataString(),
                provider = it.userMetadata?.get("provider").asMetadataString(),
                createdAt = it.createdAt?.toString(),
            )
        }

    companion object {
        const val COMPONENT = "SupabaseAuthRepository"

        /**
         * Redirect target for [resetPasswordForEmail].
         *
         * It keeps the LEGACY `nextpage` scheme deliberately: the outgoing URL
         * must be present in the Supabase redirect allowlist, which is a project
         * setting this repository cannot migrate, so switching it to the
         * canonical `nexo://` would break password reset for every user until
         * that list is updated out of band. Both schemes are accepted on the way
         * in (see [com.nexo.presentation.navigation.DeepLinkSchemes]).
         */
        const val RESET_PASSWORD_REDIRECT_URL = "nextpage://auth/reset-password"
        const val SIGNUP_STALE_SESSION_CODE = "SIGNUP_STALE_SESSION"
        const val SIGNUP_CONFIRMATION_PENDING_CODE = "SIGNUP_EMAIL_CONFIRMATION_PENDING"

        /**
         * Unified failure message for sign-up when no fresh session is created
         * and a previous session is still active. Indistinguishable on purpose:
         * "email already registered" and "confirmation email pending" look the
         * same without an extra call (REQ-auth-email-register-login-3).
         */
        const val SIGNUP_UNIFIED_MESSAGE =
            "This email is already registered or has a pending confirmation — check your inbox or sign in"

        /**
         * Message for a brand-new sign-up when the project requires email
         * confirmation: the account was created, but the user must confirm the
         * email before signing in.
         */
        const val SIGNUP_CONFIRMATION_PENDING_MESSAGE =
            "Account created — check your inbox to confirm your email and activate it."
    }
}

/**
 * Decides whether [signUpWith] produced a NEW account session.
 *
 * A stale persisted session is the failure mode fixed here: supabase-kt keeps
 * the previous session readable after a `signUpWith(Email)` call that did not
 * create a new session (already-registered email, or confirmation-pending
 * sign-up), so reading `currentSessionOrNull()` naively returns the OLD user
 * and the app would navigate a wrong account into Home.
 *
 * @param priorUserId user id of the session captured BEFORE `signUpWith`.
 * @param freshUserId user id of the session read AFTER `signUpWith`.
 * @return `true` only when a fresh session exists AND it differs from (or the
 *   prior was absent) — i.e. sign-up genuinely created a new account.
 */
internal fun isSignUpNewSession(
    priorUserId: String?,
    freshUserId: String?,
): Boolean = freshUserId != null && (priorUserId == null || priorUserId != freshUserId)

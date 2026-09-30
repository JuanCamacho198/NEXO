// Legacy backward-compat implementation, kept until the Supabase migration is
// complete. Uses the deprecated Google Sign-In API by design.
@file:Suppress("DEPRECATION")

package com.nexo.data.repository

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.services.drive.DriveScopes
import com.nexo.data.session.SessionManager
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import com.nexo.domain.repository.AuthRepository
import java.util.UUID

/**
 * @deprecated Replaced by [SupabaseAuthRepository].
 * Will be removed in the next release cycle.
 */

/**
 * AuthRepository implementation using Credential Manager One Tap + Google Sign-In.
 *
 * One Tap returns an idToken (verified client-side by Google). A full GoogleSignInAccount
 * is obtained via GoogleSignInClient for Drive API access token.
 */
@Deprecated("Replaced by SupabaseAuthRepository", ReplaceWith("SupabaseAuthRepository"))
class GoogleAuthRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val clientId: String,
    private val diagnosticError: AppError? = null,
    private val isClientAvailable: Boolean = true,
    private val googleSignInClientFactory: () -> GoogleSignInClient? = {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions
                .Builder()
                .requestIdToken(clientId)
                .requestEmail()
                .requestProfile()
                .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
                .build(),
        )
    },
) : AuthRepository {
    private val credentialManager = CredentialManager.create(context)

    override suspend fun signInWithGoogle(): Result<AuthSession> {
        if (!isClientAvailable) {
            return Result.failure(missingClientError())
        }

        val rawNonce = UUID.randomUUID().toString()

        val googleIdOption: GetGoogleIdOption =
            GetGoogleIdOption
                .Builder()
                .setServerClientId(clientId)
                .setNonce(rawNonce.hashCode().toString())
                .setFilterByAuthorizedAccounts(false)
                .build()

        val request: GetCredentialRequest =
            GetCredentialRequest
                .Builder()
                .addCredentialOption(googleIdOption)
                .build()

        return try {
            val result: GetCredentialResponse =
                credentialManager.getCredential(
                    context = context,
                    request = request,
                )
            handleOneTapResult(result)
        } catch (e: GetCredentialCancellationException) {
            Result.failure(
                AppError(
                    category = ErrorCategory.AUTH,
                    code = "GOOGLE_AUTH_CANCELLED",
                    message = "Sign in cancelled by user",
                    component = COMPONENT,
                ),
            )
        } catch (e: GetCredentialException) {
            Result.failure(
                AppError(
                    category = ErrorCategory.AUTH,
                    code = "GOOGLE_AUTH_CREDENTIAL_ERROR",
                    message = e.message ?: "Google One Tap credential error",
                    component = COMPONENT,
                ),
            )
        }
    }

    private suspend fun handleOneTapResult(result: GetCredentialResponse): Result<AuthSession> {
        return runCatching {
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw AppError(
                    category = ErrorCategory.AUTH,
                    code = "GOOGLE_AUTH_WRONG_CREDENTIAL_TYPE",
                    message = "Unexpected credential type returned by One Tap.",
                    component = COMPONENT,
                )
            }

            val googleIdTokenCredential: GoogleIdTokenCredential =
                try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (e: GoogleIdTokenParsingException) {
                    throw AppError(
                        category = ErrorCategory.AUTH,
                        code = "GOOGLE_AUTH_TOKEN_PARSE_FAILED",
                        message = e.message ?: "Failed to parse Google ID token credential.",
                        component = COMPONENT,
                    )
                }

            val session =
                AuthSession(
                    userId = googleIdTokenCredential.id,
                    email = googleIdTokenCredential.id,
                    displayName = googleIdTokenCredential.displayName,
                    photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                )
            sessionManager.setCurrentSession(session)
            return Result.success(session)
        }.getOrElse { throwable ->
            if (throwable is AppError) {
                Result.failure(throwable)
            } else {
                Result.failure(
                    AppError(
                        category = ErrorCategory.AUTH,
                        code = "GOOGLE_AUTH_UNEXPECTED",
                        message = throwable.message ?: "Unexpected error during Google sign-in.",
                        component = COMPONENT,
                    ),
                )
            }
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthSession> =
        Result.failure(
            AppError(
                category = ErrorCategory.AUTH,
                code = "GOOGLE_AUTH_DEPRECATED",
                message = "Deprecated. Use SupabaseAuthRepository.",
                component = COMPONENT,
            ),
        )

    override suspend fun startGoogleSignIn(): Result<String> =
        Result.failure(
            AppError(
                category = ErrorCategory.AUTH,
                code = "GOOGLE_AUTH_DEPRECATED",
                message = "startGoogleSignIn is deprecated. Use signInWithGoogle() for One Tap.",
                component = COMPONENT,
            ),
        )

    override suspend fun completeGoogleSignIn(callbackUri: String): Result<AuthSession?> =
        Result.failure(
            AppError(
                category = ErrorCategory.AUTH,
                code = "GOOGLE_AUTH_DEPRECATED",
                message = "completeGoogleSignIn is deprecated. Use signInWithGoogle() for One Tap.",
                component = COMPONENT,
            ),
        )

    override suspend fun signIn(
        email: String,
        password: String,
    ): Result<AuthSession> =
        Result.failure(
            UnsupportedOperationException("Email/password auth is disabled; use Google sign-in."),
        )

    override suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
    ): Result<AuthSession> =
        Result.failure(
            UnsupportedOperationException("Email/password sign-up is disabled; use Google sign-in."),
        )

    override suspend fun signOut(): Result<Unit> = sessionManager.signOutAll()

    override suspend fun getCurrentSession(): Result<AuthSession?> = sessionManager.getCurrentSession()

    override suspend fun signInLocally(): Result<AuthSession> {
        val session =
            AuthSession(
                userId = "local-${UUID.randomUUID()}",
                email = null,
                displayName = "Local User",
            )
        return sessionManager.setCurrentSession(session).map { session }
    }

    private fun missingClientError(): AppError =
        diagnosticError ?: AppError(
            category = ErrorCategory.WIRING_ERROR,
            code = "GOOGLE_AUTH_CLIENT_NOT_AVAILABLE",
            message = "Google auth requires a valid OAuth client ID.",
            component = COMPONENT,
        )

    companion object {
        const val COMPONENT = "GoogleAuthRepository"
    }
}

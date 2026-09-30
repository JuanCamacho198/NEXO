package com.nexo.domain.repository

import com.nexo.domain.model.AuthSession

interface AuthRepository {
    /** @deprecated Browser-based OAuth. Use [signInWithGoogleIdToken] instead. */
    suspend fun startGoogleSignIn(): Result<String>

    /** @deprecated Browser-based OAuth. Use [signInWithGoogleIdToken] instead. */
    suspend fun completeGoogleSignIn(callbackUri: String): Result<AuthSession?>

    /**
     * Sign in with Google via browser OAuth.
     * @deprecated Browser OAuth flow is deprecated. Use [signInWithGoogleIdToken] instead.
     */
    suspend fun signInWithGoogle(): Result<AuthSession> = Result.failure(UnsupportedOperationException("Not implemented"))

    /**
     * Sign in with a Google ID token obtained from Credential Manager (native).
     * Callers should obtain the ID token via Credential Manager at the UI layer
     * and pass it here.
     *
     * @param idToken The Google ID token from Credential Manager.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthSession>

    suspend fun signIn(
        email: String,
        password: String,
    ): Result<AuthSession>

    /**
     * Registers a new account with email and password.
     *
     * @param email User email address.
     * @param password Account password.
     * @param fullName User's full name, persisted as `full_name` metadata.
     */
    suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
    ): Result<AuthSession>

    /**
     * Sends a password-reset email for [email].
     *
     * Default implementation returns [UnsupportedOperationException] so existing
     * fakes/stubs keep compiling; only the Supabase-backed repository implements it.
     */
    suspend fun resetPassword(email: String): Result<Unit> = Result.failure(UnsupportedOperationException("Not implemented"))

    suspend fun signOut(): Result<Unit>

    suspend fun getCurrentSession(): Result<AuthSession?>

    suspend fun signInLocally(): Result<AuthSession>
}

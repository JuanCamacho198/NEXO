package com.nexo.domain.auth

import com.nexo.domain.model.AuthSession

/** Coarse classification of an auth failure, for UI copy selection. */
/** UI state of the authentication flow (sign-in / sign-up / sign-out). */
enum class AuthFailureKind {
    /** No failure — session is healthy or has not yet been attempted. */
    NONE,

    /** Failure caused by missing/malformed auth configuration (build flag, keys). */
    CONFIG_ERROR,

    /** Failure caused by a wiring/dependency problem in the auth chain. */
    WIRING_ERROR,

    /** Sign-up created the account but email confirmation is pending. */
    CONFIRMATION_PENDING,

    /** Failure that does not match the known categories. */
    UNKNOWN,
}

/**
 * AuthUiState — UI state for the authentication flow (sign-in / sign-up / sign-out).
 *
 * **Used by**: AuthScreen
 * **Mutated by**: [AuthViewModel.handleGoogleIdToken], [AuthViewModel.signUp],
 *                 [AuthViewModel.signIn], [AuthViewModel.signOut],
 *                 [AuthViewModel.continueLocally], [AuthViewModel.clearError],
 *                 and the init-block session restoration.
 *
 * @property currentSession The active auth session, or `null` if signed out / not yet known.
 * @property isConfigured `true` if the auth subsystem is configured at runtime (build keys present).
 * @property hasWiringIssue `true` if a DI/dependency wiring problem was detected at startup.
 * @property isLoading `true` while an auth call is in flight.
 * @property errorMessage Human-readable error string from the last failed call (or `null`).
 * @property failureKind Coarse classification of [errorMessage] (see [AuthFailureKind]).
 */
data class AuthUiState(
    val currentSession: AuthSession? = null,
    val isCheckingSession: Boolean = true,
    val isConfigured: Boolean = true,
    val hasWiringIssue: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val failureKind: AuthFailureKind = AuthFailureKind.NONE,
)

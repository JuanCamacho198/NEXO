package com.nexo.data.remote.drive

import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory

/**
 * Builds the OAuth redirect URI for the Android Drive flow, following Google's
 * reserved native-app pattern (`com.googleusercontent.apps.<client_id>:/oauth2redirect`).
 *
 * The scheme is derived from the ANDROID OAuth client ID (`GOOGLE_OAUTH_ANDROID_CLIENT_ID`
 * from local.properties) so no client-ID literal lives in Kotlin code. Arbitrary custom
 * URI schemes (e.g. `nexo://...`) are no longer supported by Google on Android.
 * The Android client is a PUBLIC client — it needs no `client_secret`; the web client
 * ID would require a secret and fails with `invalid_client`.
 *
 * Must match the intent-filter scheme injected into AndroidManifest.xml
 * (`driveRedirectScheme` manifest placeholder, derived the same way).
 */
fun driveOAuthRedirectUri(clientId: String): String {
    val schemePrefix = clientId.removeSuffix(".apps.googleusercontent.com")
    return "com.googleusercontent.apps.$schemePrefix:/oauth2redirect"
}

/**
 * One PKCE authorization attempt, produced by [DriveOAuthSession.beginAuth].
 *
 * @property verifier Random 43-char code_verifier — sent to the token endpoint at exchange time.
 * @property challenge S256 code_challenge of [verifier] — sent in the authorize URL.
 * @property state Random CSRF token echoed through the redirect to prove the response
 *   belongs to this attempt (prevents login-CSRF).
 */
data class AuthStart(
    val verifier: String,
    val challenge: String,
    val state: String,
)

/**
 * Outcome of [DriveOAuthSession.complete].
 *
 * Sealed so the UI can treat **cancellation as a distinct, silent state**: `Canceled`
 * is never an error and must never produce an error toast. Only [Success] persists tokens.
 */
sealed interface DriveAuthResult {
    /** The user canceled the flow before a redirect was received. Silent — never toast. */
    data object Canceled : DriveAuthResult

    /** Tokens exchanged and persisted; [accessToken] is the freshly issued access token. */
    data class Success(
        val accessToken: String,
    ) : DriveAuthResult

    /** The flow failed; [error] carries the mapped [AppError] — surface an actionable message. */
    data class Failure(
        val error: AppError,
    ) : DriveAuthResult
}

/**
 * Pure, JVM-testable core of the Drive OAuth authorization-code + PKCE flow.
 *
 * Responsibilities:
 * - [beginAuth] issues a fresh verifier/challenge/state triple per attempt.
 * - [complete] enforces CSRF via the echoed [state], maps a missing code to
 *   [DriveAuthResult.Canceled], exchanges the code through [DriveTokenApi] with the
 *   **real** code_verifier and redirect URI, and persists the token pair **only** on
 *   success (never partial tokens).
 *
 * This class has no Android dependency: the thin Android layer ([GoogleDriveAuthHelper])
 * owns URI parsing / browser launching and delegates the protocol here.
 *
 * @param clientId Android OAuth client ID (no client_secret needed for public clients).
 * @param redirectUri Must equal [driveOAuthRedirectUri] output (used both in the authorize
 *   URL and, critically, in the token exchange — Google rejects mismatched redirect URIs).
 * @param tokenStore Persisted token sink; written exclusively on [DriveAuthResult.Success].
 * @param tokenApi Token-endpoint boundary (mocked in unit tests).
 */
class DriveOAuthSession(
    val clientId: String,
    val redirectUri: String,
    private val tokenStore: DriveTokenStore,
    private val tokenApi: DriveTokenApi,
) {
    /**
     * Start a fresh authorization attempt. The caller keeps the returned [AuthStart]
     * and must pass its `state`/`verifier` back into [complete] when the redirect arrives.
     */
    fun beginAuth(): AuthStart {
        val verifier = Pkce.generateVerifier()
        return AuthStart(
            verifier = verifier,
            challenge = Pkce.challenge(verifier),
            state = Pkce.generateVerifier(),
        )
    }

    /**
     * Finish the authorization attempt described by [expectedState]/[verifier].
     *
     * @param code Authorization code from the redirect, or null when the redirect
     *   carried no code (canceled).
     * @param expectedState The state issued by [beginAuth] (CSRF check).
     * @param returnedState The state echoed back in the redirect query.
     * @param verifier The verifier issued by [beginAuth]; a mismatch with the challenge
     *   sent at authorize time makes Google reject the exchange (nothing persists).
     */
    suspend fun complete(
        code: String?,
        expectedState: String,
        returnedState: String?,
        verifier: String,
    ): DriveAuthResult {
        if (returnedState != expectedState) {
            return DriveAuthResult.Failure(
                AppError(
                    category = ErrorCategory.AUTH,
                    code = "DRIVE_OAUTH_STATE_MISMATCH",
                    message = "Drive authorization state mismatch. Please try again.",
                    component = COMPONENT,
                ),
            )
        }
        if (code.isNullOrBlank()) {
            return DriveAuthResult.Canceled
        }
        return tokenApi.exchange(clientId, code, redirectUri, verifier).fold(
            onSuccess = { pair ->
                tokenStore.persist(pair)
                DriveAuthResult.Success(pair.accessToken)
            },
            onFailure = { throwable ->
                DriveAuthResult.Failure(
                    throwable as? AppError ?: AppError(
                        category = ErrorCategory.NETWORK,
                        code = "DRIVE_OAUTH_EXCHANGE_FAILED",
                        message = "Could not reach Google to finish Drive authorization.",
                        component = COMPONENT,
                    ),
                )
            },
        )
    }

    /** True when the underlying store holds Drive tokens. */
    fun isAuthorized(): Boolean = tokenStore.isAuthorized()

    /** Remove all stored Drive tokens (disconnect). */
    fun disconnect() {
        tokenStore.clear()
    }

    companion object {
        const val COMPONENT = "DriveOAuthSession"
    }
}

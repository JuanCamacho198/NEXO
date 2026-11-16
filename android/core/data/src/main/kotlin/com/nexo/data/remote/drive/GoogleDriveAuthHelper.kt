package com.nexo.data.remote.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nexo.data.remote.google.GoogleDriveConfig
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin Android layer for the Google Drive OAuth flow (authorization-code + PKCE,
 * `drive.file` scope). The actual protocol lives in the pure, JVM-testable
 * [DriveOAuthSession]; this class only:
 *
 * 1. Builds the Google authorize URL (`client_id`, `redirect_uri` following Google's
 *    reserved native-app pattern `com.googleusercontent.apps.<android-client-id>:/oauth2redirect`,
 *    S256 `code_challenge`, `state`, `scope=drive.file`, `access_type=offline`, `prompt=consent`).
 * 2. Launches it in the browser via a plain [Intent.ACTION_VIEW] (no Custom Tabs / GoogleSignIn dep).
 * 3. Receives the redirect in [onRedirect] (driven from `MainActivity.onNewIntent`),
 *    parses `code`/`state`/`error`, and delegates to [DriveOAuthSession.complete].
 * 4. Exposes the outcome on [authResult] so any UI (Settings, and later the import prompt)
 *    observes the singleton flow and pending redirect state.
 *
 * The prior GoogleSignIn `requestServerAuthCode` path is gone: that API cannot carry a
 * PKCE challenge, which is exactly why the exchange always failed with `invalid_client`.
 * The client ID used is the ANDROID OAuth client (public client, no secret); the web
 * client ID would require `client_secret` and must NOT be used here.
 *
 * This is a separate, independent OAuth flow from Supabase auth.
 */
class GoogleDriveAuthHelper(
    private val context: Context,
    private val session: DriveOAuthSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The in-flight [AuthStart] between [beginAuth] and the redirect — singleton-pending. */
    private var pendingAuth: AuthStart? = null

    private val _authResult = MutableStateFlow<DriveAuthResult?>(null)

    /**
     * Outcome of the most recent authorization attempt, or null while idle.
     *
     * UI collects this to receive redirect-driven results: [DriveAuthResult.Success] →
     * authorized, [DriveAuthResult.Failure] → actionable toast, [DriveAuthResult.Canceled]
     * → silent. Consumers must call [consumeResult] after handling so the next attempt
     * starts clean.
     */
    val authResult: StateFlow<DriveAuthResult?> = _authResult.asStateFlow()

    /** True when Drive tokens are present. */
    fun isAuthorized(): Boolean = session.isAuthorized()

    /** Remove all stored Drive tokens (disconnect). */
    fun disconnect() {
        session.disconnect()
    }

    /**
     * Start a fresh authorization attempt and return the browser [Intent] to launch
     * (via an activity-result launcher). The pending verifier/state is stored on this
     * singleton so [onRedirect] can complete the same attempt.
     */
    fun beginAuth(): Intent {
        val auth = session.beginAuth()
        pendingAuth = auth
        val authUrl = buildAuthUrl(auth)
        Log.d(TAG, "Launching Drive OAuth browser flow")
        return Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Handle the Drive OAuth redirect (called from `MainActivity.onNewIntent`).
     * [uri] carries `code` + `state`, or `error` when the user denied the grant.
     * Parses and delegates to [DriveOAuthSession.complete], then publishes the
     * result on [authResult].
     */
    fun onRedirect(uri: Uri?) {
        val pending =
            pendingAuth ?: run {
                Log.w(TAG, "Drive OAuth redirect received with no pending authorization — ignoring")
                return
            }
        pendingAuth = null

        if (uri == null) {
            _authResult.value = DriveAuthResult.Canceled
            return
        }
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            _authResult.value = DriveAuthResult.Failure(userDeniedError(error))
            return
        }
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        scope.launch {
            _authResult.value = session.complete(code, pending.state, returnedState, pending.verifier)
        }
    }

    /** Reset the outcome channel after a consumer handled [authResult]. */
    fun consumeResult() {
        _authResult.value = null
    }

    private fun buildAuthUrl(auth: AuthStart): String =
        Uri
            .parse(GoogleDriveConfig.GOOGLE_OAUTH_AUTH_ENDPOINT)
            .buildUpon()
            .appendQueryParameter("client_id", session.clientId)
            .appendQueryParameter("redirect_uri", session.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", GoogleDriveConfig.DRIVE_FILE_SCOPE)
            .appendQueryParameter("code_challenge", auth.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", auth.state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
            .toString()

    private fun userDeniedError(error: String): AppError =
        AppError(
            category = ErrorCategory.AUTH,
            code = if (error == "access_denied") "DRIVE_OAUTH_DENIED" else "DRIVE_OAUTH_ERROR",
            message =
                if (error == "access_denied") {
                    "Google Drive authorization was declined."
                } else {
                    "Google Drive authorization failed: $error"
                },
            component = COMPONENT,
        )

    companion object {
        private const val TAG = "GoogleDriveAuthHelper"
        private const val COMPONENT = "GoogleDriveAuthHelper"
    }
}

/**
 * Purely in-memory [DriveTokenStore] fallback used when EncryptedSharedPreferences
 * cannot be built (e.g., key corruption). Not used in production; kept for symmetry
 * and trivial testability.
 */
class InMemoryDriveTokenStore : DriveTokenStore {
    private var access: String? = null
    private var refresh: String? = null

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun isAuthorized(): Boolean = !access.isNullOrBlank()

    override fun persist(pair: DriveTokenPair) {
        access = pair.accessToken
        refresh = pair.refreshToken
    }

    override fun clear() {
        refresh = null
        access = null
    }
}

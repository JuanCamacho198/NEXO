package com.nexo.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.BuildConfig
import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.repository.SupabaseAuthRepository
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import com.nexo.domain.repository.AuthRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.presentation.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Named

/**
 * Classifies the source of an auth failure so the UI can react differently
 * (e.g. show a "fix config" CTA for [CONFIG_ERROR] vs. a generic error toast
 * for [UNKNOWN]).
 */
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

/**
 * AuthViewModel — Owns the user's authentication state and exposes it as
 * [uiState] for [com.nexo.presentation.screen.AuthScreen]. Handles
 * Google One Tap sign-in, email/password sign-up and sign-in, anonymous
 * local continuation, and sign-out. On successful sign-in it bootstraps
 * remote sync via [SyncOrchestrator].
 *
 * @param authRepository Remote auth operations (Google, email, local session).
 * @param syncOrchestrator Per-domain sync lifecycle (Drive + Catalog + Progress).
 *                        Replaces the previous 4-step in-line orchestration in
 *                        [triggerSyncForSession] and the previous Progress-only
 *                        [signOut]. See sync-layer-split PR-3.
 * @param isAuthConfigured Build-time flag indicating auth keys are wired in.
 * @param hasAuthWiringIssue Build-time flag indicating a DI wiring problem.
 */
@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val syncOrchestrator: SyncOrchestrator,
        @Named("isAuthConfigured") private val isAuthConfigured: Boolean,
        @Named("hasAuthWiringIssue") private val hasAuthWiringIssue: Boolean,
        /**
         * S6: schedules an outbox drain at app start for a restored session.
         * Defaults to a no-op so direct VM tests stay free of WorkManager.
         */
        private val outboxDrainScheduler: OutboxDrainScheduler = OutboxDrainScheduler.NoOp,
    ) : ViewModel() {
        companion object {
            private const val AUTH_VM_TAG = "AuthViewModel"

            /**
             * Maximum wall-clock budget for [SyncOrchestrator.stop] during sign-out.
             * After this, local session state is cleared regardless of stop progress
             * so a slow fan-out can never block the UI. 5 s covers the typical
             * Drive stop + catalog stopRealtime + progress stopRealtime (5 channels
             * total) with margin for cancellation propagation.
             */
            private const val ORCHESTRATOR_STOP_TIMEOUT_MS = 5_000L
        }

        private val _uiState = MutableStateFlow(AuthUiState())

        /**
         * Current authentication state for the AuthScreen.
         *
         * **Emits when**: init (configuration flags + restored session), any auth
         *                action (sign-in, sign-up, sign-out, continue-locally),
         *                or [clearError] is called.
         * **Initial value**: [AuthUiState] with `isConfigured = true`, `hasWiringIssue = false`,
         *                    no session, not loading, no error.
         * **Lifecycle**: hot, lifetime-scoped to the ViewModel.
         */
        val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

        private val _uiEvent = MutableSharedFlow<UiEvent>()

        /**
         * One-shot UI events for snackbars (errors from failed auth calls).
         *
         * **Emits when**: a sign-in / sign-up / sign-out / continue-locally call fails.
         * **Backpressure**: SharedFlow with default buffer; no replay for past events.
         */
        val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

        init {
            _uiState.update {
                it.copy(
                    isConfigured = isAuthConfigured,
                    hasWiringIssue = hasAuthWiringIssue,
                )
            }
            logDiagnostics("init")
            restoreSessionOnStart()
        }

        private fun restoreSessionOnStart() {
            viewModelScope.launch {
                val t0 = System.currentTimeMillis()
                val sessionResult = authRepository.getCurrentSession()
                logDebug("restore: getCurrentSession took ${System.currentTimeMillis() - t0}ms")
                val session = sessionResult.getOrNull()

                // Release the splash the moment the session is known. Sync must NEVER
                // gate the first frame: on a slow network its sequential retries (Drive
                // ×3 × 10s timeout) held the logo for ~30s. Sync runs afterwards in its
                // own coroutine, fully async from the splash.
                if (_uiState.value.currentSession == null) {
                    _uiState.update {
                        it.copy(
                            currentSession = session,
                            errorMessage = sessionResult.exceptionOrNull()?.message,
                            failureKind = classifyFailure(sessionResult.exceptionOrNull()),
                            isCheckingSession = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isCheckingSession = false) }
                }
                logDebug("restore: isCheckingSession=false after ${System.currentTimeMillis() - t0}ms from start")

                session?.let { restored ->
                    viewModelScope.launch {
                        val t1 = System.currentTimeMillis()
                        triggerSyncForSession(restored)
                        logDebug("restore: triggerSyncForSession took ${System.currentTimeMillis() - t1}ms")
                    }
                }
            }
        }

        /**
         * Completes Google sign-in with an ID token obtained from Credential Manager.
         * The UI layer is responsible for token acquisition; the token is exchanged
         * with Supabase via [AuthRepository.signInWithGoogleIdToken].
         *
         * Side effects:
         * 1. Sets `isLoading = true` and clears any prior `errorMessage`/`failureKind`.
         * 2. Calls [AuthRepository.signInWithGoogleIdToken] — token exchange with Supabase.
         * 3. On success: triggers sync for the new session and updates `currentSession`.
         * 4. On failure: sets `errorMessage`/`failureKind` and emits a `ShowSnackbar` event.
         */
        fun handleGoogleIdToken(idToken: String) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = null,
                        failureKind = AuthFailureKind.NONE,
                    )
                }
                logDiagnostics("handleGoogleIdToken:loading")
                // DEBUG E2E bypass: mock Google login without Supabase network call.
                // Only active in DEBUG builds with the exact test token, safe for prod.
                if (BuildConfig.DEBUG && idToken == "test-token-jcamachomolina503") {
                    val fakeSession =
                        AuthSession(
                            userId = "test-jcamachomolina503",
                            email = "jcamachomolina503@gmail.com",
                            displayName = "Mock Google jcamachomolina503",
                            provider = "google",
                            providerToken = idToken,
                        )
                    triggerSyncForSession(fakeSession)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentSession = fakeSession,
                            errorMessage = null,
                            failureKind = AuthFailureKind.NONE,
                        )
                    }
                    logDiagnostics("handleGoogleIdToken:mock-success")
                    return@launch
                }
                val result = authRepository.signInWithGoogleIdToken(idToken)
                result.fold(
                    onSuccess = { session ->
                        triggerSyncForSession(session)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                currentSession = session,
                                errorMessage = null,
                                failureKind = AuthFailureKind.NONE,
                            )
                        }
                        logDiagnostics("handleGoogleIdToken:success")
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message,
                                failureKind = classifyFailure(error),
                            )
                        }
                        _uiEvent.emit(UiEvent.ShowSnackbar(error.message ?: "Authentication failed"))
                        logDiagnostics("handleGoogleIdToken:failure")
                    },
                )
            }
        }

        /**
         * @deprecated Use [handleGoogleIdToken] instead. Credential Manager is now
         * launched from the UI layer; this method throws to prevent accidental use.
         */
        @Deprecated("Use handleGoogleIdToken instead")
        fun startGoogleSignIn() {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = "Google sign-in flow has changed. Please restart the app.",
                        failureKind = AuthFailureKind.WIRING_ERROR,
                    )
                }
                _uiEvent.emit(UiEvent.ShowSnackbar("Sign-in method updated. Please try again."))
            }
        }

        @Deprecated("One Tap handles auth directly; OAuth callback flow is no longer used.")
        fun onGoogleAuthCallback(callbackUri: String) {
            // No-op: One Tap no longer uses browser callback
        }

        /**
         * Signs the user up with email, password, and full name.
         *
         * Side effects:
         * 1. Sets `isLoading = true`, clears `errorMessage`/`failureKind`.
         * 2. Calls [AuthRepository.signUp] with the full name (persisted as
         *    `full_name` metadata).
         * 3. On result: clears loading, sets `currentSession` (or leaves null on
         *    failure) and classifies `failureKind` (stale-session fix: a previous
         *    failure must never leak its kind into this call's result).
         * 4. On failure: emits a `ShowSnackbar` event with the error message.
         *
         * @param email User email address.
         * @param password Account password.
         * @param fullName User's full name (shown in the Home header).
         */
        fun signUp(
            email: String,
            password: String,
            fullName: String,
        ) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = null,
                        failureKind = AuthFailureKind.NONE,
                    )
                }
                val result = authRepository.signUp(email, password, fullName)
                val exception = result.exceptionOrNull()
                // Confirmation-pending is not an error: the account was created and
                // the user just needs to confirm by email. Keep the red error text
                // clear and surface only the snackbar, then navigate to sign-in.
                val isConfirmationPending =
                    exception is AppError &&
                        exception.code == SupabaseAuthRepository.SIGNUP_CONFIRMATION_PENDING_CODE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentSession = result.getOrNull(),
                        errorMessage = if (isConfirmationPending) null else exception?.message,
                        failureKind = classifyFailure(exception),
                    )
                }
                if (exception != null) {
                    _uiEvent.emit(UiEvent.ShowSnackbar(exception.message ?: "Sign up failed"))
                }
            }
        }

        /**
         * Signs the user in with email and password.
         *
         * Side effects:
         * 1. Sets `isLoading = true`, clears `errorMessage`/`failureKind`.
         * 2. Calls [AuthRepository.signIn].
         * 3. On result: clears loading, sets `currentSession` (or leaves null on
         *    failure) and classifies `failureKind` (stale-session fix — same as
         *    [signUp]).
         * 4. On failure: emits a `ShowSnackbar` event with the error message.
         *
         * @param email User email address.
         * @param password Account password.
         */
        fun signIn(
            email: String,
            password: String,
        ) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = null,
                        failureKind = AuthFailureKind.NONE,
                    )
                }
                val result = authRepository.signIn(email, password)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentSession = result.getOrNull(),
                        errorMessage = result.exceptionOrNull()?.message,
                        failureKind = classifyFailure(result.exceptionOrNull()),
                    )
                }
                result.exceptionOrNull()?.let {
                    _uiEvent.emit(UiEvent.ShowSnackbar(it.message ?: "Sign in failed"))
                }
            }
        }

        /**
         * Sends a password-reset email for [email].
         *
         * Side effects:
         * 1. Sets `isLoading = true`, clears `errorMessage`/`failureKind`.
         * 2. Calls [AuthRepository.resetPassword].
         * 3. On success: clears any error and emits a `ShowSnackbar` confirming
         *    the reset email was sent.
         * 4. On failure: sets `errorMessage`/`failureKind` and emits a
         *    `ShowSnackbar` event.
         *
         * @param email User email address.
         */
        fun resetPassword(email: String) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = null,
                        failureKind = AuthFailureKind.NONE,
                    )
                }
                val result = authRepository.resetPassword(email)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message,
                        failureKind = classifyFailure(result.exceptionOrNull()),
                    )
                }
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(errorMessage = null, failureKind = AuthFailureKind.NONE) }
                        _uiEvent.emit(UiEvent.ShowSnackbar("Reset email sent"))
                    },
                    onFailure = { error ->
                        _uiEvent.emit(UiEvent.ShowSnackbar(error.message ?: "Failed to send reset email"))
                    },
                )
            }
        }

        /**
         * Signs the user out and clears the local session.
         *
         * Side effects:
         * 1. Calls [SyncOrchestrator.stop] (with a 5 s timeout) BEFORE clearing
         *    local state. This closes ALL Realtime channels (4 Progress + 1
         *    Catalog + Drive) and cancels all gated loops — fixing the
         *    catalog-logout Realtime leak that the previous Progress-only stop
         *    left open. Local state is cleared AFTER orchestrator.stop returns
         *    OR after 5 s, whichever comes first, so a slow stop can never
         *    block sign-out.
         * 2. Calls [AuthRepository.signOut].
         * 3. On success: `currentSession` becomes `null`.
         * 4. On failure: `currentSession` is preserved, `errorMessage` is set,
         *    and a `ShowSnackbar` event is emitted.
         */
        fun signOut() {
            viewModelScope.launch {
                withTimeoutOrNull(ORCHESTRATOR_STOP_TIMEOUT_MS) {
                    runCatching { syncOrchestrator.stop() }
                }
                val result = authRepository.signOut()
                _uiState.update {
                    it.copy(
                        currentSession = if (result.isSuccess) null else it.currentSession,
                        errorMessage = result.exceptionOrNull()?.message,
                        failureKind = classifyFailure(result.exceptionOrNull()),
                    )
                }
                result.exceptionOrNull()?.let {
                    _uiEvent.emit(UiEvent.ShowSnackbar(it.message ?: "Sign out failed"))
                }
            }
        }

        /**
         * Continues without a remote account — creates / restores a local-only session.
         *
         * Side effects:
         * 1. Sets `isLoading = true`.
         * 2. Calls [AuthRepository.signInLocally].
         * 3. On result: clears loading, sets `currentSession` (or leaves null on failure).
         * 4. On failure: emits a `ShowSnackbar` event with the error message.
         */
        fun continueLocally() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val result = authRepository.signInLocally()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentSession = result.getOrNull(),
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
                result.exceptionOrNull()?.let {
                    _uiEvent.emit(UiEvent.ShowSnackbar(it.message ?: "Failed to continue locally"))
                }
            }
        }

        /**
         * Clears the current error message and failure kind from [uiState].
         *
         * Side effects:
         * 1. Sets `errorMessage = null` and `failureKind = AuthFailureKind.NONE`.
         * 2. Does not emit a `UiEvent` — purely a local state reset (e.g. when the
         *    user dismisses the error banner in the UI).
         */

        /**
         * Sets a user-facing error message and emits a snackbar.
         * Used by the UI layer for errors that happen before the auth call (e.g.,
         * unexpected credential type from Credential Manager).
         */
        fun setError(message: String) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = message,
                        failureKind = AuthFailureKind.UNKNOWN,
                    )
                }
                _uiEvent.emit(UiEvent.ShowSnackbar(message))
            }
        }

        fun clearError() {
            _uiState.update { it.copy(errorMessage = null, failureKind = AuthFailureKind.NONE) }
        }

        private fun logDiagnostics(context: String) {
            val state = _uiState.value
            runCatching {
                Log.d(
                    AUTH_VM_TAG,
                    "Auth diagnostics [$context]: configured=${state.isConfigured}, wiring=${state.hasWiringIssue}, loading=${state.isLoading}, failure=${state.failureKind}",
                )
            }
        }

        /** Debug timing log — safe in JVM tests where android.util.Log is not mocked. */
        private fun logDebug(message: String) {
            runCatching { Log.d(AUTH_VM_TAG, message) }
        }

        private fun classifyFailure(error: Throwable?): AuthFailureKind =
            when {
                error is AppError && error.code == SupabaseAuthRepository.SIGNUP_CONFIRMATION_PENDING_CODE ->
                    AuthFailureKind.CONFIRMATION_PENDING
                (error as? AppError)?.category == ErrorCategory.CONFIG_ERROR -> AuthFailureKind.CONFIG_ERROR
                (error as? AppError)?.category == ErrorCategory.WIRING_ERROR -> AuthFailureKind.WIRING_ERROR
                error == null -> AuthFailureKind.NONE
                else -> AuthFailureKind.UNKNOWN
            }

        private suspend fun triggerSyncForSession(session: AuthSession) {
            // sync-layer-split PR-3: collapse the previous 4-step in-line
            // orchestration (Drive bootstrap → Drive pull → Drive push → Progress
            // startProcessing → Progress subscribe → Catalog bootstrap) into a
            // single call. The orchestrator preserves Drive-first ordering AND
            // the Drive-non-fatal semantic, so triggerSyncForSession no longer
            // needs to know the per-domain shape.
            val stepStart = System.currentTimeMillis()
            runCatching { syncOrchestrator.start(session.userId) }
            logDebug("triggerSync: orchestrator.start took ${System.currentTimeMillis() - stepStart}ms")

            // S6: app-start drain — schedule one outbox drain for this session.
            // This is scheduler-only; the drain itself stays in-process.
            val drainStart = System.currentTimeMillis()
            runCatching { outboxDrainScheduler.scheduleDrain() }
            logDebug("triggerSync: scheduleDrain took ${System.currentTimeMillis() - drainStart}ms")
        }

        /**
         * ViewModelProvider.Factory for [AuthViewModel].
         *
         * Use when the ViewModel cannot be constructor-injected by the DI container
         * (e.g. legacy `viewModels()` call sites).
         */
        class Factory(
            private val authRepository: AuthRepository,
            private val syncOrchestrator: SyncOrchestrator,
            private val isAuthConfigured: Boolean,
            private val hasAuthWiringIssue: Boolean,
            private val outboxDrainScheduler: OutboxDrainScheduler = OutboxDrainScheduler.NoOp,
        ) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthViewModel(
                    authRepository = authRepository,
                    syncOrchestrator = syncOrchestrator,
                    isAuthConfigured = isAuthConfigured,
                    hasAuthWiringIssue = hasAuthWiringIssue,
                    outboxDrainScheduler = outboxDrainScheduler,
                ) as T
        }
    }

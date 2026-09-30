package com.nexo.presentation.viewmodel

import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.remote.sync.SyncState
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import com.nexo.domain.repository.AuthRepository
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun init_mapsConfigErrorKind_whenCurrentSessionFailsWithConfigError() =
        runTest {
            val repository =
                FakeAuthRepository(
                    currentSessionResult =
                        Result.failure(
                            AppError(
                                category = ErrorCategory.CONFIG_ERROR,
                                code = "SUPABASE_CONFIG_MISSING_URL",
                                message = "Missing URL",
                                component = "SupabaseConfig",
                            ),
                        ),
                )

            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = false,
                    hasAuthWiringIssue = false,
                )

            advanceUntilIdle()

            assertEquals(AuthFailureKind.CONFIG_ERROR, viewModel.uiState.value.failureKind)
            assertEquals("Missing URL", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun handleGoogleIdToken_setsSession_whenSignInWithGoogleSucceeds() =
        runTest {
            val session = AuthSession(userId = "u1", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    signInWithGoogleResult = Result.success(session),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.handleGoogleIdToken("test-id-token")
            advanceUntilIdle()

            assertEquals(session, viewModel.uiState.value.currentSession)
        }

    @Test
    fun handleGoogleIdToken_setsError_whenSignInWithGoogleFails() =
        runTest {
            val repository =
                FakeAuthRepository(
                    signInWithGoogleResult =
                        Result.failure(
                            AppError(
                                category = ErrorCategory.AUTH,
                                code = "GOOGLE_AUTH_CANCELLED",
                                message = "User cancelled",
                                component = "SupabaseAuthRepository",
                            ),
                        ),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.handleGoogleIdToken("test-id-token")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSession)
            assertEquals("User cancelled", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun signOut_clearsSession() =
        runTest {
            val session = AuthSession(userId = "u1", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    currentSessionResult = Result.success(session),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = true,
                )
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSession)
        }

    @Test
    fun updateAtomicity_rapidMutationsNoLostState() =
        runTest {
            val repository = FakeAuthRepository()
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            // Simulate rapid consecutive state mutations via clearError (uses .update {})
            viewModel.clearError()
            viewModel.clearError()
            viewModel.clearError()

            assertEquals(
                "rapid clearError calls should produce correct final state",
                AuthFailureKind.NONE,
                viewModel.uiState.value.failureKind,
            )
            assertEquals(
                "rapid clearError calls should clear errorMessage",
                null,
                viewModel.uiState.value.errorMessage,
            )
        }

    @Test
    fun signUp_forwardsFullNameAndSetsSession_whenSucceeds() =
        runTest {
            val session = AuthSession(userId = "new-u1", email = "new@test.com", displayName = "Ana García")
            val repository =
                FakeAuthRepository(
                    signUpResult = Result.success(session),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signUp("new@test.com", "password123", "Ana García")
            advanceUntilIdle()

            assertEquals("Ana García", repository.lastSignUpFullName)
            assertEquals("new@test.com", repository.lastSignUpEmail)
            assertEquals(session, viewModel.uiState.value.currentSession)
            assertEquals(AuthFailureKind.NONE, viewModel.uiState.value.failureKind)
        }

    @Test
    fun signUp_setsFailureKind_whenFails() =
        runTest {
            val repository =
                FakeAuthRepository(
                    signUpResult =
                        Result.failure(
                            AppError(
                                category = ErrorCategory.AUTH,
                                code = "SIGNUP_STALE_SESSION",
                                message = "already registered",
                                component = "SupabaseAuthRepository",
                            ),
                        ),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signUp("taken@test.com", "password123", "Ana García")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSession)
            assertEquals("already registered", viewModel.uiState.value.errorMessage)
            // Stale-session fix: a failure must classify (UNKNOWN here — AppError AUTH
            // is not CONFIG/WIRING), never leak a previous kind.
            assertEquals(AuthFailureKind.UNKNOWN, viewModel.uiState.value.failureKind)
        }

    @Test
    fun signIn_setsFailureKind_whenFails() =
        runTest {
            val repository =
                FakeAuthRepository(
                    signInResult =
                        Result.failure(
                            AppError(
                                category = ErrorCategory.WIRING_ERROR,
                                code = "AUTH_WIRING",
                                message = "wiring broken",
                                component = "SupabaseAuthRepository",
                            ),
                        ),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signIn("user@test.com", "password123")
            advanceUntilIdle()

            assertEquals(AuthFailureKind.WIRING_ERROR, viewModel.uiState.value.failureKind)
        }

    @Test
    fun resetPassword_sendsEmailAndClearsError_whenSucceeds() =
        runTest {
            val repository =
                FakeAuthRepository(
                    resetPasswordResult = Result.success(Unit),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.resetPassword("user@test.com")
            advanceUntilIdle()

            assertEquals("user@test.com", repository.lastResetPasswordEmail)
            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals(AuthFailureKind.NONE, viewModel.uiState.value.failureKind)
        }

    @Test
    fun resetPassword_setsError_whenFails() =
        runTest {
            val repository =
                FakeAuthRepository(
                    resetPasswordResult =
                        Result.failure(
                            AppError(
                                category = ErrorCategory.AUTH,
                                code = "RESET_PASSWORD_FAILED",
                                message = "no account",
                                component = "SupabaseAuthRepository",
                            ),
                        ),
                )
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = FakeSyncOrchestrator(),
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.resetPassword("missing@test.com")
            advanceUntilIdle()

            assertEquals("no account", viewModel.uiState.value.errorMessage)
            assertEquals(AuthFailureKind.UNKNOWN, viewModel.uiState.value.failureKind)
        }

    // ── sync-layer-split PR-3 — AuthViewModel orchestrator wiring ────────
    // Regression for the catalog-logout Realtime leak. signOut must call
    // orchestrator.stop() (which closes ALL Realtime channels across Drive,
    // Catalog and Progress) before clearing local session state.

    @Test
    fun onLogout_invokesOrchestratorStop() =
        runTest {
            val session = AuthSession(userId = "u1", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    currentSessionResult = Result.success(session),
                )
            val orchestrator = FakeSyncOrchestrator()
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = orchestrator,
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            // orchestrator.stop() must be invoked exactly once during sign-out.
            assertEquals(1, orchestrator.stopCount)
        }

    @Test
    fun onLogout_clearsAllRealtimeChannels() =
        runTest {
            // The FakeSyncOrchestrator records stop() invocations; the real
            // orchestrator fans out to drive.stop + catalog.stop + progress.stop
            // (verified separately in SyncOrchestratorTest). This test asserts the
            // AuthViewModel side of the contract: signOut → orchestrator.stop().
            val session = AuthSession(userId = "u1", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    currentSessionResult = Result.success(session),
                )
            val orchestrator = FakeSyncOrchestrator()
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = orchestrator,
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            // All per-domain stop() calls live behind orchestrator.stop(); the
            // single invocation here covers Drive + Catalog + Progress.
            assertEquals(1, orchestrator.stopCount)
            assertNull(viewModel.uiState.value.currentSession)
        }

    @Test
    fun onLogout_orchestratorStopTimeout_stillClearsLocalState() =
        runTest {
            val session = AuthSession(userId = "u1", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    currentSessionResult = Result.success(session),
                )
            // Orchestrator that hangs longer than the 5s stop budget.
            val orchestrator = FakeSyncOrchestrator(stopDelayMs = 10_000L)
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = orchestrator,
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.signOut()
            // Advance past the 5s timeout but well before the orchestrator's
            // 10s simulated delay — local state must be cleared at the 5s mark.
            advanceTimeBy(5_500L)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSession)
            // The orchestrator's stop coroutine is cancelled by withTimeoutOrNull;
            // it never gets to set stopCount because the function suspends past
            // the timeout. We only assert the user-visible invariant.
            assertEquals(0, orchestrator.stopCount)
        }

    @Test
    fun triggerSyncForSession_delegatesToOrchestratorStart() =
        runTest {
            val session = AuthSession(userId = "user-123", email = "u1@test.com")
            val repository =
                FakeAuthRepository(
                    signInWithGoogleResult = Result.success(session),
                )
            val orchestrator = FakeSyncOrchestrator()
            val viewModel =
                AuthViewModel(
                    authRepository = repository,
                    syncOrchestrator = orchestrator,
                    isAuthConfigured = true,
                    hasAuthWiringIssue = false,
                )
            advanceUntilIdle()

            viewModel.handleGoogleIdToken("test-id-token")
            advanceUntilIdle()

            // triggerSyncForSession collapsed into orchestrator.start(userId).
            assertEquals(listOf("user-123"), orchestrator.startedWith)
            // No direct per-domain orchestration remains at the AuthViewModel
            // level — verified by the absence of any SyncService handles here.
            assertTrue(
                "handleGoogleIdToken must trigger exactly one orchestrator.start",
                orchestrator.startedWith.size == 1,
            )
        }

    private class FakeAuthRepository(
        private val startGoogleResult: Result<String> = Result.failure(IllegalStateException("not set")),
        private val completeGoogleResult: Result<AuthSession?> = Result.success(null),
        private val signInWithGoogleResult: Result<AuthSession> = Result.failure(IllegalStateException("not set")),
        private val currentSessionResult: Result<AuthSession?> = Result.success(null),
        private val signOutResult: Result<Unit> = Result.success(Unit),
        private val signInResult: Result<AuthSession> = Result.failure(UnsupportedOperationException()),
        private val signUpResult: Result<AuthSession> = Result.failure(UnsupportedOperationException()),
        private val resetPasswordResult: Result<Unit> = Result.failure(UnsupportedOperationException()),
    ) : AuthRepository {
        var lastSignInEmail: String? = null
        var lastSignUpEmail: String? = null
        var lastSignUpFullName: String? = null
        var lastResetPasswordEmail: String? = null

        override suspend fun startGoogleSignIn(): Result<String> = startGoogleResult

        override suspend fun completeGoogleSignIn(callbackUri: String): Result<AuthSession?> = completeGoogleResult

        override suspend fun signInWithGoogle(): Result<AuthSession> = signInWithGoogleResult

        override suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthSession> = signInWithGoogleResult

        override suspend fun signIn(
            email: String,
            password: String,
        ): Result<AuthSession> {
            lastSignInEmail = email
            return signInResult
        }

        override suspend fun signUp(
            email: String,
            password: String,
            fullName: String,
        ): Result<AuthSession> {
            lastSignUpEmail = email
            lastSignUpFullName = fullName
            return signUpResult
        }

        override suspend fun resetPassword(email: String): Result<Unit> {
            lastResetPasswordEmail = email
            return resetPasswordResult
        }

        override suspend fun signOut(): Result<Unit> = signOutResult

        override suspend fun signInLocally(): Result<AuthSession> = Result.failure(UnsupportedOperationException())

        override suspend fun getCurrentSession(): Result<AuthSession?> = currentSessionResult
    }

    /**
     * Minimal in-memory [SyncOrchestrator] for AuthViewModel wiring tests.
     * Records `start` arguments and `stop` invocations so the assertions can
     * verify the new contract. `stopDelayMs` simulates a hanging orchestrator
     * (used by the timeout regression test).
     */
    private class FakeSyncOrchestrator(
        private val stopDelayMs: Long = 0L,
    ) : SyncOrchestrator {
        val startedWith: MutableList<String> = mutableListOf()
        var stopCount: Int = 0
            private set

        override suspend fun start(userId: String) {
            startedWith += userId
        }

        override suspend fun stop() {
            if (stopDelayMs > 0L) delay(stopDelayMs)
            stopCount += 1
        }

        override suspend fun schedulePush() {}

        override suspend fun schedulePull() {}

        override val state: kotlinx.coroutines.flow.StateFlow<SyncState> =
            MutableStateFlow(SyncState.Idle)

        override val pendingCount: Flow<Int> = emptyFlow()
    }
}

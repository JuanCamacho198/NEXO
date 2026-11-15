package com.nexo.presentation.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.presentation.viewmodel.AuthViewModel

// AuthViewModel is a :app Hilt type shared by MainActivity, DebugPanel,
// AuthNavGraph, SettingsNavGraph and ViewModelProviders, so it cannot move into
// :feature:auth. Only the state-bound `*Content` composables live in the
// module; these three thin entry points bind the ViewModel to them.

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onContinueLocal: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgot: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LoginScreenContent(
        uiState = uiState,
        onAuthenticated = onAuthenticated,
        onContinueLocal = onContinueLocal,
        onNavigateToRegister = onNavigateToRegister,
        onNavigateToForgot = onNavigateToForgot,
        onGoogleIdToken = viewModel::handleGoogleIdToken,
        onSetError = viewModel::setError,
        onSignIn = viewModel::signIn,
    )
}

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegisterScreenContent(
        uiState = uiState,
        onAuthenticated = onAuthenticated,
        onNavigateBack = onNavigateBack,
        onGoogleIdToken = viewModel::handleGoogleIdToken,
        onSetError = viewModel::setError,
        onSignUp = viewModel::signUp,
    )
}

@Composable
fun ForgotScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ForgotScreenContent(
        uiState = uiState,
        onAuthenticated = onAuthenticated,
        onNavigateBack = onNavigateBack,
        onResetPassword = viewModel::resetPassword,
    )
}

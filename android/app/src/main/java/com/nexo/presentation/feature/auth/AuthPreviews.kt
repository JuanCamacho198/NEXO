package com.nexo.presentation.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.AuthFailureKind
import com.nexo.presentation.viewmodel.AuthUiState

@Preview(showBackground = true)
@Composable
private fun AuthScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        LoginScreenContent(
            uiState = AuthUiState(currentSession = null, isCheckingSession = false, isConfigured = true, hasWiringIssue = false, isLoading = false, errorMessage = null, failureKind = AuthFailureKind.NONE),
            onAuthenticated = {},
            onContinueLocal = {},
            onNavigateToRegister = {},
            onNavigateToForgot = {},
            onGoogleIdToken = {},
            onSetError = {},
            onSignIn = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        LoginScreenContent(
            uiState = AuthUiState(currentSession = null, isCheckingSession = false, isConfigured = true, hasWiringIssue = false, isLoading = false, errorMessage = null, failureKind = AuthFailureKind.NONE),
            onAuthenticated = {},
            onContinueLocal = {},
            onNavigateToRegister = {},
            onNavigateToForgot = {},
            onGoogleIdToken = {},
            onSetError = {},
            onSignIn = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RegisterScreenPreview() {
    NexoTheme(darkTheme = false) {
        RegisterScreenContent(
            uiState = AuthUiState(currentSession = null, isCheckingSession = false, isConfigured = true, hasWiringIssue = false, isLoading = false, errorMessage = null, failureKind = AuthFailureKind.NONE),
            onAuthenticated = {},
            onNavigateBack = {},
            onGoogleIdToken = {},
            onSetError = {},
            onSignUp = { _, _, _ -> },
        )
    }
}

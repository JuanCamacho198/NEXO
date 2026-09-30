package com.nexo.presentation.screen

import androidx.compose.runtime.Composable
import com.nexo.presentation.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onContinueLocal: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgot: () -> Unit,
) {
    com.nexo.presentation.feature.auth
        .AuthScreen(viewModel = viewModel, onAuthenticated = onAuthenticated, onContinueLocal = onContinueLocal, onNavigateToRegister = onNavigateToRegister, onNavigateToForgot = onNavigateToForgot)
}

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    com.nexo.presentation.feature.auth
        .RegisterScreen(viewModel = viewModel, onAuthenticated = onAuthenticated, onNavigateBack = onNavigateBack)
}

@Composable
fun ForgotScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    com.nexo.presentation.feature.auth
        .ForgotScreen(viewModel = viewModel, onAuthenticated = onAuthenticated, onNavigateBack = onNavigateBack)
}

package com.nexo.presentation.feature.auth

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.presentation.screen.AuthTags
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.AuthFailureKind
import com.nexo.presentation.viewmodel.AuthUiState
import com.nexo.presentation.viewmodel.AuthViewModel
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

private const val AUTH_SCREEN_TAG = "AuthScreen"

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
internal fun LoginScreenContent(
    uiState: AuthUiState,
    onAuthenticated: () -> Unit,
    onContinueLocal: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgot: () -> Unit,
    onGoogleIdToken: (String) -> Unit,
    onSetError: (String) -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
) {
    val buttonDisabledReason = resolveGoogleButtonDisabledReason(uiState)
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(uiState.currentSession) {
        if (uiState.currentSession != null) onAuthenticated()
    }
    LaunchedEffect(buttonDisabledReason, uiState.failureKind) {
        Log.d(AUTH_SCREEN_TAG, "Google diagnostics: $buttonDisabledReason, configured=${uiState.isConfigured}, wiring=${uiState.hasWiringIssue}, loading=${uiState.isLoading}, failure=${uiState.failureKind}")
    }
    AuthScreenScaffold(showBackArrow = false, onNavigateBack = {}) {
        AuthLogo()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text =
                buildAnnotatedString {
                    append(stringResource(R.string.auth_welcome_back_prefix))
                    withStyle(SpanStyle(color = NexoTheme.colors.welcomeBrandBlue)) {
                        append(" ")
                        append(stringResource(R.string.auth_welcome_back_accent))
                    }
                },
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.auth_login_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        AuthConfigError(uiState)
        NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
        Spacer(modifier = Modifier.height(8.dp))
        PasswordTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.auth_password_label))
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNavigateToForgot, modifier = Modifier.testTag(AuthTags.FORGOT_LINK)) { Text(text = stringResource(R.string.auth_forgot_password), style = MaterialTheme.typography.bodyMedium) }
        }
        NexoButton(onClick = { onSignIn(email, password) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(56.dp).testTag(AuthTags.SIGNIN)) {
            Text(text = stringResource(R.string.auth_login_action), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(imageVector = NexoIcons.ArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        AuthOrDivider(text = stringResource(R.string.auth_or_login_with))
        Spacer(modifier = Modifier.height(16.dp))
        GoogleSignInButton(uiState = uiState, onGoogleIdToken = onGoogleIdToken, onSetError = onSetError)
        AuthDevBypass(onContinueLocal)
        AuthErrorText(uiState)
        Spacer(modifier = Modifier.height(24.dp))
        AuthFooterLink(prefix = stringResource(R.string.auth_register_footer_prefix), link = stringResource(R.string.auth_register_footer_link), onClick = onNavigateToRegister, modifier = Modifier.testTag(AuthTags.REGISTER_LINK))
    }
}

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegisterScreenContent(uiState = uiState, onAuthenticated = onAuthenticated, onNavigateBack = onNavigateBack, onGoogleIdToken = viewModel::handleGoogleIdToken, onSetError = viewModel::setError, onSignUp = viewModel::signUp)
}

@Composable
internal fun RegisterScreenContent(
    uiState: AuthUiState,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
    onGoogleIdToken: (String) -> Unit,
    onSetError: (String) -> Unit,
    onSignUp: (email: String, password: String, fullName: String) -> Unit,
) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(uiState.currentSession) { if (uiState.currentSession != null) onAuthenticated() }
    LaunchedEffect(uiState.failureKind) { if (uiState.failureKind == AuthFailureKind.CONFIRMATION_PENDING) onNavigateBack() }
    AuthScreenScaffold(showBackArrow = true, onNavigateBack = onNavigateBack) {
        Text(text = stringResource(R.string.auth_register_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        NexoTextField(value = fullName, onValueChange = { fullName = it }, label = stringResource(R.string.auth_full_name_label), placeholder = stringResource(R.string.auth_full_name_placeholder), singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.FULLNAME))
        Spacer(modifier = Modifier.height(8.dp))
        NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
        Spacer(modifier = Modifier.height(8.dp))
        PasswordTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.auth_password_label), hint = stringResource(R.string.auth_password_hint))
        Spacer(modifier = Modifier.height(16.dp))
        NexoButton(onClick = { if (isPasswordValid(password)) onSignUp(email, password, fullName) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AuthTags.SIGNUP)) { Text(text = stringResource(R.string.auth_register_action), fontWeight = FontWeight.SemiBold) }
        Spacer(modifier = Modifier.height(24.dp))
        AuthOrDivider(text = stringResource(R.string.auth_or_register_with))
        Spacer(modifier = Modifier.height(16.dp))
        GoogleSignInButton(uiState = uiState, onGoogleIdToken = onGoogleIdToken, onSetError = onSetError)
        AuthErrorText(uiState)
        Spacer(modifier = Modifier.height(24.dp))
        AuthFooterLink(prefix = stringResource(R.string.auth_login_footer_prefix), link = stringResource(R.string.auth_login_footer_link), onClick = onNavigateBack, modifier = Modifier.testTag(AuthTags.LOGIN_LINK))
    }
}

@Composable
fun ForgotScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ForgotScreenContent(uiState = uiState, onAuthenticated = onAuthenticated, onNavigateBack = onNavigateBack, onResetPassword = viewModel::resetPassword)
}

@Composable
internal fun ForgotScreenContent(
    uiState: AuthUiState,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
    onResetPassword: (email: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    LaunchedEffect(uiState.currentSession) { if (uiState.currentSession != null) onAuthenticated() }
    AuthScreenScaffold(showBackArrow = true, onNavigateBack = onNavigateBack) {
        Text(text = stringResource(R.string.auth_forgot_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.auth_forgot_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
        Spacer(modifier = Modifier.height(16.dp))
        NexoButton(onClick = { onResetPassword(email) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AuthTags.SEND_RESET)) { Text(text = stringResource(R.string.auth_send_reset_email), fontWeight = FontWeight.SemiBold) }
        AuthErrorText(uiState)
    }
}

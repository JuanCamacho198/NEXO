package com.nexo.presentation.feature.auth

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexo.domain.auth.AuthFailureKind
import com.nexo.domain.auth.AuthUiState
import com.nexo.feature.auth.R
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.tags.AuthTags

private const val AUTH_SCREEN_TAG = "AuthScreen"

@Composable
fun LoginScreenContent(
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
    var emailFormExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.currentSession) {
        if (uiState.currentSession != null) onAuthenticated()
    }
    LaunchedEffect(buttonDisabledReason, uiState.failureKind) {
        Log.d(AUTH_SCREEN_TAG, "Google diagnostics: $buttonDisabledReason, configured=${uiState.isConfigured}, wiring=${uiState.hasWiringIssue}, loading=${uiState.isLoading}, failure=${uiState.failureKind}")
    }
    AuthScreenScaffold(showBackArrow = false, onNavigateBack = {}) {
        AuthTitle(title = stringResource(R.string.auth_welcome_title), subtitle = stringResource(R.string.auth_login_subtitle))
        AuthConfigError(uiState)
        GoogleSignInButton(uiState = uiState, onGoogleIdToken = onGoogleIdToken, onSetError = onSetError)
        Spacer(modifier = Modifier.height(12.dp))
        // The toggle gives way to the form it opens, instead of lingering as a
        // second button that no longer does anything.
        AnimatedVisibility(visible = !emailFormExpanded, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
            NexoButton(
                onClick = { emailFormExpanded = true },
                enabled = !uiState.isLoading,
                variant = NexoButtonVariant.OUTLINED,
                shape = AuthButtonShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth().height(AuthButtonHeight).testTag(AuthTags.EMAIL_TOGGLE),
            ) {
                Icon(imageVector = NexoIcons.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = stringResource(R.string.auth_continue_with_email), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        AnimatedVisibility(visible = emailFormExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AuthOrDivider(text = stringResource(R.string.auth_or_login_with))
                Spacer(modifier = Modifier.height(16.dp))
                NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
                Spacer(modifier = Modifier.height(8.dp))
                PasswordTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.auth_password_label))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onNavigateToForgot, modifier = Modifier.testTag(AuthTags.FORGOT_LINK)) { Text(text = stringResource(R.string.auth_forgot_password), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                }
                NexoButton(onClick = { onSignIn(email, password) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = AuthButtonShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(AuthButtonHeight).testTag(AuthTags.SIGNIN)) {
                    Text(text = stringResource(R.string.auth_login_action), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(imageVector = NexoIcons.ArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
        AuthDevBypass(onContinueLocal)
        AuthErrorText(uiState)
        Spacer(modifier = Modifier.height(16.dp))
        AuthFooterLink(prefix = stringResource(R.string.auth_register_footer_prefix), link = stringResource(R.string.auth_register_footer_link), onClick = onNavigateToRegister, modifier = Modifier.testTag(AuthTags.REGISTER_LINK))
    }
}

/** Sheet heading shared by the three auth screens: bold title, quiet subtitle. */
@Composable
private fun AuthTitle(
    title: String,
    subtitle: String? = null,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val AuthButtonShape = RoundedCornerShape(16.dp)
private val AuthButtonHeight = 56.dp

@Composable
fun RegisterScreenContent(
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
        AuthTitle(title = stringResource(R.string.auth_register_title))
        Spacer(modifier = Modifier.height(24.dp))
        NexoTextField(value = fullName, onValueChange = { fullName = it }, label = stringResource(R.string.auth_full_name_label), placeholder = stringResource(R.string.auth_full_name_placeholder), singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.FULLNAME))
        Spacer(modifier = Modifier.height(8.dp))
        NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
        Spacer(modifier = Modifier.height(8.dp))
        PasswordTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.auth_password_label), hint = stringResource(R.string.auth_password_hint))
        Spacer(modifier = Modifier.height(16.dp))
        NexoButton(onClick = { if (isPasswordValid(password)) onSignUp(email, password, fullName) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = AuthButtonShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(AuthButtonHeight).testTag(AuthTags.SIGNUP)) { Text(text = stringResource(R.string.auth_register_action), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
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
fun ForgotScreenContent(
    uiState: AuthUiState,
    onAuthenticated: () -> Unit,
    onNavigateBack: () -> Unit,
    onResetPassword: (email: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    LaunchedEffect(uiState.currentSession) { if (uiState.currentSession != null) onAuthenticated() }
    AuthScreenScaffold(showBackArrow = true, onNavigateBack = onNavigateBack) {
        AuthTitle(title = stringResource(R.string.auth_forgot_title), subtitle = stringResource(R.string.auth_forgot_subtitle))
        Spacer(modifier = Modifier.height(24.dp))
        NexoTextField(value = email, onValueChange = { email = it }, label = stringResource(R.string.auth_email_label), leadingIcon = NexoIcons.Email, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AuthTags.EMAIL))
        Spacer(modifier = Modifier.height(16.dp))
        NexoButton(onClick = { onResetPassword(email) }, enabled = !uiState.isLoading, variant = NexoButtonVariant.FILLED, shape = AuthButtonShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().height(AuthButtonHeight).testTag(AuthTags.SEND_RESET)) { Text(text = stringResource(R.string.auth_send_reset_email), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        AuthErrorText(uiState)
    }
}

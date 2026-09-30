package com.nexo.presentation.feature.auth

import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.nexo.BuildConfig
import com.nexo.R
import com.nexo.presentation.screen.AuthTags
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.AuthUiState
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.util.safePainterResource
import kotlinx.coroutines.launch
import java.util.Locale

private const val AUTH_SCREEN_TAG = "AuthScreen"

internal enum class GoogleButtonDisabledReason {
    NONE,
    LOADING,
    CONFIG_ERROR,
    WIRING_ERROR,
}

internal fun resolveGoogleButtonDisabledReason(uiState: AuthUiState): GoogleButtonDisabledReason =
    when {
        uiState.isLoading -> GoogleButtonDisabledReason.LOADING
        !uiState.isConfigured -> GoogleButtonDisabledReason.CONFIG_ERROR
        uiState.hasWiringIssue -> GoogleButtonDisabledReason.WIRING_ERROR
        else -> GoogleButtonDisabledReason.NONE
    }

internal fun googleButtonDisabledReasonMessageRes(reason: GoogleButtonDisabledReason): Int? =
    when (reason) {
        GoogleButtonDisabledReason.LOADING -> R.string.auth_google_disabled_loading
        GoogleButtonDisabledReason.CONFIG_ERROR -> R.string.auth_google_disabled_config_error
        GoogleButtonDisabledReason.WIRING_ERROR -> R.string.auth_google_disabled_wiring_error
        GoogleButtonDisabledReason.NONE -> null
    }

@Composable
fun GoogleSignInButton(
    uiState: AuthUiState,
    onGoogleIdToken: (String) -> Unit,
    onSetError: (String) -> Unit,
) {
    val buttonDisabledReason = resolveGoogleButtonDisabledReason(uiState)
    val buttonEnabled = buttonDisabledReason == GoogleButtonDisabledReason.NONE
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val googleContainerColor =
        if (isDarkBackground) Color.White else NexoTheme.colors.welcomeBrandBlue
    val googleContentColor =
        if (isDarkBackground) Color(0xFF202124) else Color.White

    val unexpectedCredentialTemplate = stringResource(R.string.auth_google_unexpected_credential)
    val credentialErrorTemplate = stringResource(R.string.auth_google_credential_error)
    val unexpectedErrorTemplate = stringResource(R.string.auth_google_unexpected_error)

    NexoButton(
        onClick = {
            val googleIdOption =
                GetGoogleIdOption
                    .Builder()
                    .setServerClientId(BuildConfig.GOOGLE_OAUTH_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            val request =
                GetCredentialRequest
                    .Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(context)
                    val result =
                        credentialManager.getCredential(
                            context = context,
                            request = request,
                        )
                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val googleIdTokenCredential =
                            try {
                                GoogleIdTokenCredential.createFrom(credential.data)
                            } catch (e: GoogleIdTokenParsingException) {
                                Log.e(AUTH_SCREEN_TAG, "Failed to parse Google ID token", e)
                                return@launch
                            }
                        onGoogleIdToken(googleIdTokenCredential.idToken)
                    } else {
                        Log.w(AUTH_SCREEN_TAG, "Unexpected credential type: ${credential.type}")
                        onSetError(
                            unexpectedCredentialTemplate.format(Locale.getDefault(), credential.type),
                        )
                    }
                } catch (e: GetCredentialCancellationException) {
                    Log.d(AUTH_SCREEN_TAG, "Google sign-in cancelled by user")
                } catch (e: GetCredentialException) {
                    Log.e(AUTH_SCREEN_TAG, "Google credential error: type=${e.type} msg=${e.message}", e)
                    onSetError(
                        credentialErrorTemplate.format(Locale.getDefault(), e.type, e.message),
                    )
                } catch (e: Exception) {
                    Log.e(AUTH_SCREEN_TAG, "Unexpected error in Google sign-in", e)
                    onSetError(
                        unexpectedErrorTemplate.format(Locale.getDefault(), e.message),
                    )
                }
            }
        },
        enabled = buttonEnabled,
        variant = NexoButtonVariant.FILLED,
        shape = RoundedCornerShape(28.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = googleContainerColor,
                contentColor = googleContentColor,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(AuthTags.GOOGLE),
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).testTag(AuthTags.LOADING),
                strokeWidth = 2.dp,
                color = googleContentColor,
            )
        } else {
            Icon(
                painter =
                    safePainterResource(
                        resId = R.drawable.ic_google_logo,
                        fallbackColor = Color.Transparent,
                        source = "google_logo",
                    ),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auth_continue_with_google),
                fontWeight = FontWeight.Medium,
            )
        }
    }

    if (!buttonEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        val disabledReasonText =
            googleButtonDisabledReasonMessageRes(buttonDisabledReason)
                ?.let { messageRes -> stringResource(messageRes) }
        if (disabledReasonText != null) {
            Text(
                text = disabledReasonText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(AuthTags.GOOGLE_DISABLED_REASON),
            )
        }
    }

    if (uiState.hasWiringIssue) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_wiring_error_incomplete),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

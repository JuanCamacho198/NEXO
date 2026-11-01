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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

/**
 * Every message [googleCredentialErrorMessageRes] can return, resolved to text.
 *
 * The coroutine that catches the credential failure is not a composable and cannot call
 * `stringResource()`, so all candidates are resolved here during composition.
 *
 * @throws IllegalStateException if a resource id produced by the mapper is missing from
 *   [CREDENTIAL_MESSAGE_RES_IDS] — a programming error, surfaced the first time the lookup runs.
 */
@Composable
private fun rememberCredentialMessageResources(): Map<Int, String> = CREDENTIAL_MESSAGE_RES_IDS.associateWith { messageRes -> stringResource(messageRes) }

private fun Map<Int, String>.credentialMessage(messageRes: Int): String =
    this[messageRes]
        ?: error(
            "No resolved message for resource $messageRes. " +
                "Add it to CREDENTIAL_MESSAGE_RES_IDS in GoogleSignInButton.kt.",
        )

private val CREDENTIAL_MESSAGE_RES_IDS =
    listOf(
        R.string.auth_google_no_credential,
        R.string.auth_google_credential_interrupted,
        R.string.auth_google_credential_network_error,
        R.string.auth_google_credential_error_generic,
        R.string.auth_google_credential_unknown,
    )

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

    // Neutral (non-error) inline feedback for TYPE_NO_CREDENTIAL: a device without a
    // Google account is a normal case, not a failure, so it must not be painted with
    // colorScheme.error like onSetError() does.
    var neutralMessageRes by remember { mutableStateOf<Int?>(null) }

    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val googleContainerColor =
        if (isDarkBackground) Color.White else NexoTheme.colors.welcomeBrandBlue
    val googleContentColor =
        if (isDarkBackground) Color(0xFF202124) else Color.White

    val unexpectedCredentialTemplate = stringResource(R.string.auth_google_unexpected_credential)
    val unexpectedErrorTemplate = stringResource(R.string.auth_google_unexpected_error)

    // Resolved up front: the coroutine below cannot call stringResource().
    // Every message googleCredentialErrorMessageRes() can return is resolved here, so the
    // catch block only has to pick one by resource id.
    val credentialMessageRes = rememberCredentialMessageResources()

    NexoButton(
        onClick = {
            // Drop inline feedback from the previous attempt before starting a new one.
            neutralMessageRes = null
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
                    when (val messageRes = googleCredentialErrorMessageRes(e.type)) {
                        null -> Unit
                        R.string.auth_google_no_credential -> neutralMessageRes = messageRes
                        else -> onSetError(credentialMessageRes.credentialMessage(messageRes))
                    }
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

    val neutralMessage = neutralMessageRes?.let { messageRes -> stringResource(messageRes) }
    if (neutralMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = neutralMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(AuthTags.GOOGLE_DISABLED_REASON),
        )
    }
}

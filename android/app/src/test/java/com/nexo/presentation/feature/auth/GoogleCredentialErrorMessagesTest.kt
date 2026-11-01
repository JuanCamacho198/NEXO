package com.nexo.presentation.feature.auth

import com.nexo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The raw `GetCredentialException.type` values below are the literal strings
 * produced by androidx.credentials 1.5.0. Note the namespace asymmetry:
 * most types live under `android.credentials.GetCredentialException.*` while
 * the network error is `androidx.credentials.TYPE_NETWORK_ERROR`.
 */
class GoogleCredentialErrorMessagesTest {
    @Test
    fun noCredential_mapsToNoCredentialMessage() {
        assertEquals(
            R.string.auth_google_no_credential,
            googleCredentialErrorMessageRes("android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL"),
        )
    }

    @Test
    fun interrupted_mapsToInterruptedMessage() {
        assertEquals(
            R.string.auth_google_credential_interrupted,
            googleCredentialErrorMessageRes("android.credentials.GetCredentialException.TYPE_INTERRUPTED"),
        )
    }

    @Test
    fun networkError_mapsToNetworkMessage() {
        assertEquals(
            R.string.auth_google_credential_network_error,
            googleCredentialErrorMessageRes("androidx.credentials.TYPE_NETWORK_ERROR"),
        )
    }

    @Test
    fun unknownType_fallsBackToNonNullMessage() {
        val resolved = googleCredentialErrorMessageRes("com.example.SOME_BRAND_NEW_TYPE")
        assertNotNull(resolved)
        assertEquals(R.string.auth_google_credential_unknown, resolved)
    }

    @Test
    fun emptyType_fallsBackToNonNullMessage() {
        assertEquals(
            R.string.auth_google_credential_unknown,
            googleCredentialErrorMessageRes(""),
        )
    }

    @Test
    fun providerConfigurationError_mapsToGenericCredentialMessage() {
        assertEquals(
            R.string.auth_google_credential_error_generic,
            googleCredentialErrorMessageRes(
                "androidx.credentials.TYPE_GET_CREDENTIAL_PROVIDER_CONFIGURATION_EXCEPTION",
            ),
        )
    }

    @Test
    fun unsupportedError_mapsToGenericCredentialMessage() {
        assertEquals(
            R.string.auth_google_credential_error_generic,
            googleCredentialErrorMessageRes("androidx.credentials.TYPE_GET_CREDENTIAL_UNSUPPORTED_EXCEPTION"),
        )
    }

    @Test
    fun userCanceled_returnsNull() {
        assertNull(googleCredentialErrorMessageRes("android.credentials.GetCredentialException.TYPE_USER_CANCELED"))
    }

    @Test
    fun userCanceledSuffix_returnsNullRegardlessOfNamespace() {
        assertNull(googleCredentialErrorMessageRes("androidx.credentials.SOMETHING.TYPE_USER_CANCELED"))
    }

    @Test
    fun typeMatchingIsSuffixBasedNotNamespaceBased() {
        assertEquals(
            R.string.auth_google_no_credential,
            googleCredentialErrorMessageRes("TYPE_NO_CREDENTIAL"),
        )
    }
}

package com.nexo.presentation.feature.auth

import com.nexo.R
import com.nexo.presentation.viewmodel.AuthFailureKind
import com.nexo.presentation.viewmodel.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthScreenDiagnosticTest {
    @Test
    fun resolveGoogleButtonDisabledReason_returnsLoadingFirst() {
        val state =
            AuthUiState(
                isConfigured = false,
                hasWiringIssue = true,
                isLoading = true,
                failureKind = AuthFailureKind.UNKNOWN,
            )

        assertEquals(
            GoogleButtonDisabledReason.LOADING,
            resolveGoogleButtonDisabledReason(state),
        )
    }

    @Test
    fun resolveGoogleButtonDisabledReason_returnsConfigErrorWhenNotConfigured() {
        val state =
            AuthUiState(
                isConfigured = false,
                hasWiringIssue = false,
                isLoading = false,
            )

        assertEquals(
            GoogleButtonDisabledReason.CONFIG_ERROR,
            resolveGoogleButtonDisabledReason(state),
        )
    }

    @Test
    fun resolveGoogleButtonDisabledReason_returnsWiringErrorWhenConfiguredButWiringBroken() {
        val state =
            AuthUiState(
                isConfigured = true,
                hasWiringIssue = true,
                isLoading = false,
            )

        assertEquals(
            GoogleButtonDisabledReason.WIRING_ERROR,
            resolveGoogleButtonDisabledReason(state),
        )
    }

    @Test
    fun resolveGoogleButtonDisabledReason_returnsNoneWhenEnabled() {
        val state =
            AuthUiState(
                isConfigured = true,
                hasWiringIssue = false,
                isLoading = false,
            )

        assertEquals(
            GoogleButtonDisabledReason.NONE,
            resolveGoogleButtonDisabledReason(state),
        )
    }

    @Test
    fun googleButtonDisabledReasonMessageRes_mapsAllDiagnosticStates() {
        assertEquals(
            R.string.auth_google_disabled_loading,
            googleButtonDisabledReasonMessageRes(GoogleButtonDisabledReason.LOADING),
        )
        assertEquals(
            R.string.auth_google_disabled_config_error,
            googleButtonDisabledReasonMessageRes(GoogleButtonDisabledReason.CONFIG_ERROR),
        )
        assertEquals(
            R.string.auth_google_disabled_wiring_error,
            googleButtonDisabledReasonMessageRes(GoogleButtonDisabledReason.WIRING_ERROR),
        )
        assertNull(googleButtonDisabledReasonMessageRes(GoogleButtonDisabledReason.NONE))
    }

    @Test
    fun authFailureMessageTemplateRes_mapsExpectedFailureKinds() {
        assertEquals(
            R.string.auth_failure_config_error_with_details,
            authFailureMessageTemplateRes(AuthFailureKind.CONFIG_ERROR),
        )
        assertEquals(
            R.string.auth_failure_wiring_error_with_details,
            authFailureMessageTemplateRes(AuthFailureKind.WIRING_ERROR),
        )
        assertNull(authFailureMessageTemplateRes(AuthFailureKind.UNKNOWN))
        assertNull(authFailureMessageTemplateRes(null))
    }
}

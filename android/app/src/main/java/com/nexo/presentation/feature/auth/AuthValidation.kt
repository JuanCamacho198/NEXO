package com.nexo.presentation.feature.auth

import com.nexo.R
import com.nexo.presentation.viewmodel.AuthFailureKind

/** Minimum password length enforced client-side on the register screen (SCEN-3). */
const val MIN_PASSWORD_LENGTH = 8

fun isPasswordValid(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

fun authFailureMessageTemplateRes(failureKind: AuthFailureKind?): Int? =
    when (failureKind) {
        AuthFailureKind.CONFIG_ERROR -> R.string.auth_failure_config_error_with_details
        AuthFailureKind.WIRING_ERROR -> R.string.auth_failure_wiring_error_with_details
        else -> null
    }

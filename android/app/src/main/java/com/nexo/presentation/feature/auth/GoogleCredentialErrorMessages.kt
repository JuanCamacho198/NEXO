package com.nexo.presentation.feature.auth

import com.nexo.R

/**
 * Raw `GetCredentialException.type` values emitted by androidx.credentials.
 *
 * Namespace is intentionally asymmetric in the upstream library: most types are
 * declared on `android.credentials.GetCredentialException`, while the DOM-derived
 * network error is `androidx.credentials.TYPE_NETWORK_ERROR`. Matching is done on
 * the `TYPE_*` suffix so a namespace change upstream does not silently drop a case.
 */
private const val SUFFIX_NO_CREDENTIAL = "TYPE_NO_CREDENTIAL"
private const val SUFFIX_INTERRUPTED = "TYPE_INTERRUPTED"
private const val SUFFIX_NETWORK_ERROR = "TYPE_NETWORK_ERROR"
private const val SUFFIX_USER_CANCELED = "TYPE_USER_CANCELED"

private const val SUFFIX_PROVIDER_CONFIGURATION = "TYPE_GET_CREDENTIAL_PROVIDER_CONFIGURATION_EXCEPTION"
private const val SUFFIX_UNSUPPORTED = "TYPE_GET_CREDENTIAL_UNSUPPORTED_EXCEPTION"
private const val SUFFIX_UNKNOWN = "TYPE_UNKNOWN"

/**
 * Maps a raw credential failure type to the string resource that should be shown.
 *
 * Returns `null` when the type needs no message — the only such case is a user
 * cancellation, which the button already handles silently.
 *
 * `TYPE_NO_CREDENTIAL` is not a failure: it means the device has no Google
 * account available. It is translated to a neutral informational message.
 */
internal fun googleCredentialErrorMessageRes(type: String): Int? =
    when {
        type.endsWith(SUFFIX_USER_CANCELED) -> null
        type.endsWith(SUFFIX_NO_CREDENTIAL) -> R.string.auth_google_no_credential
        type.endsWith(SUFFIX_INTERRUPTED) -> R.string.auth_google_credential_interrupted
        type.endsWith(SUFFIX_NETWORK_ERROR) -> R.string.auth_google_credential_network_error
        type.endsWith(SUFFIX_PROVIDER_CONFIGURATION) -> R.string.auth_google_credential_error_generic
        type.endsWith(SUFFIX_UNSUPPORTED) -> R.string.auth_google_credential_error_generic
        type.endsWith(SUFFIX_UNKNOWN) -> R.string.auth_google_credential_error_generic
        else -> R.string.auth_google_credential_unknown
    }

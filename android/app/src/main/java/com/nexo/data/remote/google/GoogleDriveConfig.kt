package com.nexo.data.remote.google

import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory

data class GoogleDriveConfig(
    val oauthClientId: String,
) {
    fun validate(component: String = COMPONENT): Result<Unit> {
        val trimmed = oauthClientId.trim()

        if (trimmed.isBlank()) {
            return Result.failure(
                AppError(
                    category = ErrorCategory.CONFIG_ERROR,
                    code = "GOOGLE_DRIVE_CONFIG_MISSING_CLIENT_ID",
                    message = "Google OAuth client ID is missing or blank.",
                    component = component,
                ),
            )
        }

        if (trimmed.contains("your-client-id", ignoreCase = true) || trimmed.contains("YOUR_CLIENT_ID")) {
            return Result.failure(
                AppError(
                    category = ErrorCategory.CONFIG_ERROR,
                    code = "GOOGLE_DRIVE_CONFIG_PLACEHOLDER_CLIENT_ID",
                    message = "Google OAuth client ID still uses a placeholder value.",
                    component = component,
                ),
            )
        }

        // Must end with .apps.googleusercontent.com
        if (!trimmed.endsWith(".apps.googleusercontent.com")) {
            return Result.failure(
                AppError(
                    category = ErrorCategory.CONFIG_ERROR,
                    code = "GOOGLE_DRIVE_CONFIG_MALFORMED_CLIENT_ID",
                    message = "Google OAuth client ID appears malformed. Expected format: <id>.apps.googleusercontent.com",
                    component = component,
                ),
            )
        }

        return Result.success(Unit)
    }

    val isConfigured: Boolean
        get() = validate().isSuccess

    companion object {
        const val COMPONENT = "GoogleDriveConfig"

        /** OAuth scope required for Drive access via the `drive.file` grant. */
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        /** Google OAuth token endpoint for exchanging auth/refresh codes. */
        const val GOOGLE_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** Google OAuth authorization endpoint used by the OAuth authorization-code flow. */
        const val GOOGLE_OAUTH_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

        // Shared Drive protocol folder, same value as DriveCatalogContract.BOOKS_PATH.
        const val DRIVE_BOOKS_PATH = "Nexo/Books"
        const val DRIVE_PROTOCOL_VERSION = 1
    }
}

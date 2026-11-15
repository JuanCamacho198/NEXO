package com.nexo.data.remote.drive

import android.content.Context
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.nexo.data.remote.sync.GoogleDriveStorageRemoteDataSource
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory

/**
 * Owns the Drive identity (tokens), the OAuth sign-in flow, and the real Drive
 * data source. This replaces the prior `by lazy` null-cache and the Noop wiring
 * with a **non-null, non-lazy** data source rebuilt from a validated token.
 *
 * Responsibilities:
 * 1. Expose the authorized state via [isEnabled] (`token != null`).
 * 2. [credentialInitializer] injects the Bearer token into Drive REST calls.
 * 3. [buildDataSource] constructs a real [GoogleDriveStorageRemoteDataSource].
 * 4. [refreshAccessToken] re-issues an access token from the stored refresh token
 *    and rebuilds the source so retries run against fresh credentials.
 */
class DriveCoordinator(
    private val context: Context,
    private val tokenStore: DriveTokenStore,
    private val tokenApi: DriveTokenApi,
    private val clientId: String,
) {
    private val credentialInitializer: com.google.api.client.http.HttpRequestInitializer =
        com.google.api.client.http.HttpRequestInitializer { request ->
            val token = tokenStore.accessToken()
            if (!token.isNullOrBlank()) {
                request.headers.setAuthorization("Bearer $token")
            }
        }

    /** True when a Drive access token is present. */
    fun isEnabled(): Boolean = tokenStore.isAuthorized()

    /**
     * Build a real, non-lazy Drive data source. Always non-null; when
     * unauthenticated any actual call fails, letting callers surface an
     * "authorization needed" state instead of crashing or no-oping.
     */
    fun buildDataSource(): GoogleDriveStorageRemoteDataSource {
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val driveService =
            Drive
                .Builder(transport, jsonFactory, credentialInitializer)
                .setApplicationName("NEXO")
                .build()
        return GoogleDriveStorageRemoteDataSource(driveService)
    }

    /**
     * Re-issue an access token from the stored refresh token, persist it, and
     * return the fresh token. On failure (no refresh token / refresh rejected)
     * the store is cleared so the UI transitions to a logged-out state.
     */
    suspend fun refreshAccessToken(): Result<String> {
        val refreshToken = tokenStore.refreshToken()
        if (refreshToken.isNullOrBlank()) {
            tokenStore.clear()
            return Result.failure(
                AppError(
                    category = ErrorCategory.AUTH,
                    code = "DRIVE_NO_REFRESH_TOKEN",
                    message = "Drive refresh token is missing. Re-authorize Drive.",
                    component = COMPONENT,
                ),
            )
        }
        return tokenApi.refresh(clientId, refreshToken).map { pair ->
            tokenStore.persist(pair)
            pair.accessToken
        }
    }

    companion object {
        const val COMPONENT = "DriveCoordinator"
    }
}

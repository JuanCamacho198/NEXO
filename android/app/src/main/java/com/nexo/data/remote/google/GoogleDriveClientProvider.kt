package com.nexo.data.remote.google

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory

data class GoogleDriveInitDiagnostic(
    val component: String,
    val status: Status,
    val message: String,
    val error: AppError? = null,
) {
    enum class Status {
        SUCCESS,
        CONFIG_ERROR,
        WIRING_ERROR,
    }
}

class GoogleDriveClientProvider(
    val config: GoogleDriveConfig,
    private val httpRequestInitializer: HttpRequestInitializer? = null,
    private val component: String = DEFAULT_COMPONENT,
    private val transportFactory: () -> NetHttpTransport = {
        GoogleNetHttpTransport.newTrustedTransport()
    },
) {
    val initDiagnostic: GoogleDriveInitDiagnostic
    val driveService: Drive?

    init {
        val validation = config.validate(component)
        if (validation.isFailure) {
            val appError =
                validation.exceptionOrNull() as? AppError ?: AppError(
                    category = ErrorCategory.CONFIG_ERROR,
                    code = "GOOGLE_DRIVE_CONFIG_UNKNOWN",
                    message = "Google Drive configuration failed validation.",
                    component = component,
                )
            driveService = null
            initDiagnostic =
                GoogleDriveInitDiagnostic(
                    component = component,
                    status = GoogleDriveInitDiagnostic.Status.CONFIG_ERROR,
                    message = appError.message,
                    error = appError,
                )
        } else {
            val created =
                runCatching {
                    val transport = transportFactory()
                    val jsonFactory = GsonFactory.getDefaultInstance()
                    Drive
                        .Builder(transport, jsonFactory, httpRequestInitializer)
                        .setApplicationName("NEXO")
                        .build()
                }

            if (created.isSuccess) {
                driveService = created.getOrNull()
                initDiagnostic =
                    GoogleDriveInitDiagnostic(
                        component = component,
                        status = GoogleDriveInitDiagnostic.Status.SUCCESS,
                        message = "Google Drive client initialized successfully.",
                    )
            } else {
                val cause = created.exceptionOrNull()
                val appError =
                    AppError(
                        category = ErrorCategory.WIRING_ERROR,
                        code = "GOOGLE_DRIVE_CLIENT_BOOTSTRAP_FAILED",
                        message = cause?.message ?: "Google Drive client bootstrap failed.",
                        component = component,
                    )
                driveService = null
                initDiagnostic =
                    GoogleDriveInitDiagnostic(
                        component = component,
                        status = GoogleDriveInitDiagnostic.Status.WIRING_ERROR,
                        message = appError.message,
                        error = appError,
                    )
            }
        }
    }

    val isConfigured: Boolean
        get() = initDiagnostic.status == GoogleDriveInitDiagnostic.Status.SUCCESS

    companion object {
        private const val DEFAULT_COMPONENT = "GoogleDriveClientProvider"
    }
}

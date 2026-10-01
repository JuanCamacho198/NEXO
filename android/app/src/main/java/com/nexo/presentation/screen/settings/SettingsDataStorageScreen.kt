package com.nexo.presentation.screen.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.BuildConfig
import com.nexo.R
import com.nexo.data.remote.drive.DriveAuthResult
import com.nexo.data.remote.drive.DriveOAuthSession
import com.nexo.data.remote.drive.DriveTokenApi
import com.nexo.data.remote.drive.DriveTokenPair
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.remote.drive.InMemoryDriveTokenStore
import com.nexo.data.remote.drive.driveOAuthRedirectUri
import com.nexo.data.remote.sync.DriveColdBackupService
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.NexoPreferenceItem
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.launch

/**
 * Data & Storage settings: Google Drive authorization via the PKCE browser flow.
 *
 * The flow is driven by the [GoogleDriveAuthHelper] singleton (see [driveAuthHelper]):
 * the authorize button launches the browser intent; the redirect arrives through
 * `MainActivity.onNewIntent` → helper, and the outcome is observed on
 * [GoogleDriveAuthHelper.authResult]. Per spec, **cancellation is silent** — the
 * spinner stops and NO toast is shown — while failures surface an actionable toast.
 */
@Composable
fun SettingsDataStorageScreen(
    driveAuthHelper: GoogleDriveAuthHelper,
    onNavigateToStatistics: () -> Unit,
    onBack: () -> Unit,
    onNavigateToStorage: () -> Unit = {},
    driveColdBackupService: DriveColdBackupService? = null,
    userId: String? = null,
) {
    val context = LocalContext.current
    var isAuthorizing by remember { mutableStateOf(false) }
    var driveAuthorized by remember { mutableStateOf(driveAuthHelper.isAuthorized()) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val coldBackupAvailable = driveColdBackupService != null && userId != null

    val oauthErrorText = stringResource(R.string.settings_drive_error_oauth)

    // Redirect-driven outcome (browser → MainActivity.onNewIntent → helper.onRedirect):
    // Success → authorized; Failure → actionable toast; Canceled → silent (no toast).
    LaunchedEffect(driveAuthHelper) {
        driveAuthHelper.authResult.collect { result ->
            if (result != null) {
                isAuthorizing = false
                when (result) {
                    is DriveAuthResult.Success -> driveAuthorized = true
                    is DriveAuthResult.Failure ->
                        Toast
                            .makeText(
                                context,
                                oauthErrorText,
                                Toast.LENGTH_SHORT,
                            ).show()
                    DriveAuthResult.Canceled -> Unit // cancellation is not an error — no toast
                }
                driveAuthHelper.consumeResult()
            }
        }
    }

    val signInLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            // The browser task closed without a redirect (user pressed back) — canceled.
            // Spinner off, NO toast. Redirect results arrive through driveAuthHelper.authResult.
            if (result.resultCode != Activity.RESULT_OK) {
                isAuthorizing = false
            }
        }

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_data_storage_title),
        onBack = onBack,
    ) {
        NexoPreferenceItem(
            icon = NexoIcons.Storage,
            label = stringResource(R.string.settings_storage),
            onClick = onNavigateToStorage,
        )

        NexoPreferenceItem(
            icon = NexoIcons.CloudSync,
            label = stringResource(R.string.settings_sync),
            onClick = {},
        )

        NexoPreferenceItem(
            icon = NexoIcons.Statistics,
            label = stringResource(R.string.settings_statistics_title),
            onClick = onNavigateToStatistics,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Google Drive Section ─────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_drive_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_drive_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            isAuthorizing -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            driveAuthorized -> {
                OutlinedButton(
                    onClick = {
                        driveAuthHelper.disconnect()
                        driveAuthorized = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(text = stringResource(R.string.settings_drive_disconnect))
                }
            }
            else -> {
                val errorConfigText = stringResource(R.string.settings_drive_error_config)
                Button(
                    onClick = {
                        val clientId = BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID
                        if (clientId.isBlank()) {
                            Toast
                                .makeText(
                                    context,
                                    errorConfigText,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            return@Button
                        }

                        isAuthorizing = true
                        signInLauncher.launch(driveAuthHelper.beginAuth())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isAuthorizing,
                ) {
                    Text(
                        text =
                            if (isAuthorizing) {
                                stringResource(R.string.settings_drive_authorizing)
                            } else {
                                stringResource(R.string.settings_drive_authorize)
                            },
                    )
                }
            }
        }

        // ── Cold Backup Section (PR3) — Settings-only, no hot path ─────────
        if (driveAuthorized) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_drive_cold_backup_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_drive_cold_backup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Export button — writes JSON+bin via GDrive parents-fix, chunk 100 not needed for export
            Button(
                onClick = {
                    if (!coldBackupAvailable || isExporting || isImporting) return@Button
                    scope.launch {
                        isExporting = true
                        try {
                            val result = driveColdBackupService!!.exportColdBackup(userId!!)
                            val msg =
                                if (result.isSuccess) {
                                    context.getString(R.string.settings_drive_cold_export_success)
                                } else {
                                    context.getString(R.string.settings_drive_cold_export_error)
                                }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: context.getString(R.string.settings_drive_cold_export_error), Toast.LENGTH_SHORT).show()
                        } finally {
                            isExporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = coldBackupAvailable && !isExporting && !isImporting,
            ) {
                if (isExporting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(if (isExporting) R.string.settings_drive_cold_exporting else R.string.settings_drive_cold_export))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (!coldBackupAvailable || isExporting || isImporting) return@OutlinedButton
                    scope.launch {
                        isImporting = true
                        try {
                            val result = driveColdBackupService!!.importColdBackup(userId!!)
                            val failures = result.getOrNull()?.failures.orEmpty()
                            val msg =
                                when {
                                    result.isFailure -> context.getString(R.string.settings_drive_cold_import_error)
                                    failures.isNotEmpty() ->
                                        context.getString(
                                            R.string.settings_drive_cold_import_partial,
                                            failures.size,
                                            failures.map { it.error.code }.distinct().joinToString(", "),
                                        )
                                    else -> context.getString(R.string.settings_drive_cold_import_success)
                                }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: context.getString(R.string.settings_drive_cold_import_error), Toast.LENGTH_SHORT).show()
                        } finally {
                            isImporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = coldBackupAvailable && !isExporting && !isImporting,
            ) {
                if (isImporting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(if (isImporting) R.string.settings_drive_cold_importing else R.string.settings_drive_cold_import))
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Composable
private fun previewDriveAuthHelper(): GoogleDriveAuthHelper =
    GoogleDriveAuthHelper(
        context = LocalContext.current,
        session =
            DriveOAuthSession(
                clientId = BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID,
                redirectUri = driveOAuthRedirectUri(BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID),
                tokenStore = InMemoryDriveTokenStore(),
                tokenApi =
                    object : DriveTokenApi {
                        override suspend fun exchange(
                            clientId: String,
                            authCode: String,
                            redirectUri: String?,
                            codeVerifier: String?,
                        ): Result<DriveTokenPair> = Result.failure(IllegalStateException("Preview stub"))

                        override suspend fun refresh(
                            clientId: String,
                            refreshToken: String,
                        ): Result<DriveTokenPair> = Result.failure(IllegalStateException("Preview stub"))
                    },
            ),
    )

@Preview(showBackground = true)
@Composable
private fun SettingsDataStorageScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsDataStorageScreen(
            driveAuthHelper = previewDriveAuthHelper(),
            onNavigateToStatistics = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsDataStorageScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsDataStorageScreen(
            driveAuthHelper = previewDriveAuthHelper(),
            onNavigateToStatistics = {},
            onBack = {},
        )
    }
}

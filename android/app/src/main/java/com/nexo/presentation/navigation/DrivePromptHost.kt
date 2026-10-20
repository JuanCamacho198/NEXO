package com.nexo.presentation.navigation

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nexo.R
import com.nexo.data.remote.drive.DriveAuthResult
import com.nexo.data.remote.drive.DriveConnectPromptGate
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.session.DriveConnectPromptPrefs
import com.nexo.domain.model.AuthSession
import com.nexo.presentation.viewmodel.LibraryImportEvent
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Consolidates the Drive connect prompt host: single collect(authResult) + consumeResult(),
 * Gate.shouldShow(), DriveConnectPromptPrefs, dialog + driveConnectAuthLauncher.
 *
 * Exactly one collect on [GoogleDriveAuthHelper.authResult] must exist (spec DrivePromptHost singleton).
 * The accept path reuses the PR2 singleton helper (same pending PKCE state as Settings).
 * importEvents success → Gate.shouldShow() → showDriveConnectPrompt.
 */
@Composable
fun DrivePromptHost(
    driveAuthHelper: GoogleDriveAuthHelper,
    prefs: DriveConnectPromptPrefs,
    authSession: AuthSession?,
    importEvents: SharedFlow<LibraryImportEvent>,
    snackbarHostState: SnackbarHostState,
    syncService: com.nexo.data.remote.sync.SyncService,
    notificationStore: com.nexo.domain.notification.NotificationStore,
    onAuthSuccessPostPush: suspend () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDriveConnectPrompt by rememberSaveable { mutableStateOf(false) }
    var drivePromptAuthInFlight by remember { mutableStateOf(false) }

    // importEvents → snackbar + sync push + Gate prompt + notification tray
    LaunchedEffect(importEvents) {
        importEvents.collect { event ->
            when (event) {
                is LibraryImportEvent.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.library_import_success, event.title),
                    )
                    scope.launch {
                        notificationStore.emit(
                            com.nexo.domain.notification.NotifyDraft(
                                source = com.nexo.domain.notification.NotificationSource.IMPORT,
                                severity = com.nexo.domain.notification.Severity.SUCCESS,
                                i18nKey = "notifications.event.import.success",
                                i18nParams = mapOf("p1" to event.title),
                                dedupKey = "import:success:${event.title}",
                                target =
                                    com.nexo.domain.notification.NotificationTarget.Route(
                                        com.nexo.domain.notification.NotificationRouteTarget.LIBRARY,
                                    ),
                            ),
                        )
                    }
                    syncService.schedulePushInBackground()
                    val shouldOfferPrompt =
                        DriveConnectPromptGate.shouldShow(
                            importSucceeded = true,
                            driveEnabled = driveAuthHelper.isAuthorized(),
                            providerIsGoogle = authSession?.provider == "google",
                            declinedForUser = prefs.declinedForUser(),
                            currentUser = authSession?.userId,
                        )
                    if (shouldOfferPrompt) showDriveConnectPrompt = true
                }
                is LibraryImportEvent.Failure -> {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.library_import_failure, event.message),
                    )
                    scope.launch {
                        notificationStore.emit(
                            com.nexo.domain.notification.NotifyDraft(
                                source = com.nexo.domain.notification.NotificationSource.IMPORT,
                                severity = com.nexo.domain.notification.Severity.ERROR,
                                i18nKey = "notifications.event.import.error",
                                i18nParams = mapOf("p1" to event.message),
                                target =
                                    com.nexo.domain.notification.NotificationTarget.Route(
                                        com.nexo.domain.notification.NotificationRouteTarget.LIBRARY,
                                    ),
                            ),
                        )
                    }
                }
            }
        }
    }

    val driveConnectAuthLauncher: ManagedActivityResultLauncher<android.content.Intent, androidx.activity.result.ActivityResult> =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) {
                drivePromptAuthInFlight = false
            }
        }

    // Singleton authResult collector — exactly one in the host
    LaunchedEffect(driveAuthHelper) {
        driveAuthHelper.authResult.collect { result ->
            if (result != null) {
                when (result) {
                    is DriveAuthResult.Success -> {
                        prefs.clearDeclined()
                        // Fire-and-forget on the service's own process-lifetime
                        // scope: the user is typically on /settings when they
                        // accept Drive, and the push must survive whatever they
                        // navigate to (NEXO-ANDROID-R). It is deliberately NOT
                        // awaited here — awaiting it from the composition is what
                        // tied the upload's lifetime to this screen.
                        syncService.schedulePushInBackground()
                    }
                    is DriveAuthResult.Failure -> {
                        if (drivePromptAuthInFlight) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.settings_drive_error_oauth),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                    DriveAuthResult.Canceled -> Unit
                }
                drivePromptAuthInFlight = false
                driveAuthHelper.consumeResult()
            }
        }
    }

    // Sync outcome collector (previously inline in host; kept here to preserve single drive host ownership).
    // Notification boundary: a SYNC notification fires on a USER-MEANINGFUL outcome only —
    // a definitive failure (`DriveSyncState.Error`) or a COMPLETED run (`Running` → `Idle`),
    // never on every internal retry (the service flips through `Running` per push pass and
    // only settles on a terminal state). The snackbar keeps its own behaviour.
    LaunchedEffect(syncService) {
        var previous: com.nexo.data.remote.sync.DriveSyncState? = null
        syncService.syncState.collect { state ->
            if (state is com.nexo.data.remote.sync.DriveSyncState.Error) {
                snackbarHostState.showSnackbar("Sync error: ${state.message}")
                notificationStore.emit(
                    com.nexo.domain.notification.NotifyDraft(
                        source = com.nexo.domain.notification.NotificationSource.SYNC,
                        severity = com.nexo.domain.notification.Severity.ERROR,
                        i18nKey = "notifications.event.sync.error",
                        i18nParams = mapOf("p1" to state.message),
                        dedupKey = "sync:error:${state.message}",
                        target =
                            com.nexo.domain.notification.NotificationTarget.Route(
                                com.nexo.domain.notification.NotificationRouteTarget.SYNC,
                            ),
                    ),
                )
            } else if (previous is com.nexo.data.remote.sync.DriveSyncState.Running &&
                state is com.nexo.data.remote.sync.DriveSyncState.Idle
            ) {
                notificationStore.emit(
                    com.nexo.domain.notification.NotifyDraft(
                        source = com.nexo.domain.notification.NotificationSource.SYNC,
                        severity = com.nexo.domain.notification.Severity.SUCCESS,
                        i18nKey = "notifications.event.sync.success",
                        target =
                            com.nexo.domain.notification.NotificationTarget.Route(
                                com.nexo.domain.notification.NotificationRouteTarget.SYNC,
                            ),
                    ),
                )
            }
            previous = state
        }
    }

    if (showDriveConnectPrompt) {
        NexoDialog(
            title = context.getString(R.string.drive_connect_prompt_title),
            body = context.getString(R.string.drive_connect_prompt_body),
            confirmText = context.getString(R.string.drive_connect_prompt_accept),
            dismissText = context.getString(R.string.drive_connect_prompt_decline),
            icon = NexoIcons.CloudDownload,
            onConfirm = {
                showDriveConnectPrompt = false
                val clientId = com.nexo.BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID
                if (clientId.isBlank()) {
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.settings_drive_error_config),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    return@NexoDialog
                }
                drivePromptAuthInFlight = true
                driveConnectAuthLauncher.launch(driveAuthHelper.beginAuth())
            },
            onDismiss = {
                showDriveConnectPrompt = false
                val userId = authSession?.userId
                DriveConnectPromptGate.markDeclined(userId)?.let {
                    prefs.persistDeclined(it)
                }
            },
        )
    }
}

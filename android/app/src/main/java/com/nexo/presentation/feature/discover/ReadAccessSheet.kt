package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.access.LegalAccess
import com.nexo.domain.usecase.DownloadImportState
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.components.atoms.NexoSkeletonBox
import com.nexo.ui.icons.NexoIcons

/**
 * "Dónde leerlo" bottom sheet (U5): grouped FREE / BUY / SUBSCRIBE rows
 * plus the in-app download CTA when the book resolves to one.
 *
 * States: [AccessResolverState.Loading] renders skeleton rows,
 * [AccessResolverState.Loaded] the grouped rows + CTA, [Empty] the
 * "sin coincidencias legales" copy, [Error]/[Offline] their copy + retry.
 * Every link opens externally (`https` only); only the gated PD download
 * flows in-app through [onDownloadInApp].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAccessSheet(
    state: AccessResolverState,
    download: DownloadImportState = DownloadImportState.Idle,
    onDownloadInApp: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAllowConsent: () -> Unit = {},
    onDenyConsent: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexoColors.surface,
    ) {
        ReadAccessContent(
            state = state,
            download = download,
            onDownloadInApp = onDownloadInApp,
            onCancelDownload = onCancelDownload,
            onRetry = onRetry,
            onAllowConsent = onAllowConsent,
            onDenyConsent = onDenyConsent,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        )
    }
}

/**
 * Full-screen variant of the access surface (U5): same states and grouped
 * rows as [ReadAccessSheet] under a top bar, for hosts that prefer a pushed
 * screen over a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAccessScreen(
    state: AccessResolverState,
    download: DownloadImportState = DownloadImportState.Idle,
    onDownloadInApp: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAllowConsent: () -> Unit = {},
    onDenyConsent: () -> Unit = {},
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.read_access_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.discover_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        ReadAccessContent(
            state = state,
            download = download,
            onDownloadInApp = onDownloadInApp,
            onCancelDownload = onCancelDownload,
            onRetry = onRetry,
            onAllowConsent = onAllowConsent,
            onDenyConsent = onDenyConsent,
            modifier =
                Modifier
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** Shared state-driven body for the sheet and the full-screen variants. */
@Composable
internal fun ReadAccessContent(
    state: AccessResolverState,
    download: DownloadImportState,
    onDownloadInApp: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    onAllowConsent: () -> Unit = {},
    onDenyConsent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.read_access_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
        )
        Text(
            text = stringResource(R.string.read_access_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
        )
        NexoDivider()
        when (state) {
            AccessResolverState.Loading -> ReadAccessSkeleton()
            is AccessResolverState.Loaded ->
                ReadAccessLoaded(
                    access = state.access,
                    download = download,
                    onDownloadInApp = onDownloadInApp,
                    onCancelDownload = onCancelDownload,
                )
            AccessResolverState.Empty ->
                ReadAccessMessage(
                    title = stringResource(R.string.read_access_empty_title),
                    body = stringResource(R.string.read_access_empty_body),
                )
            AccessResolverState.Error -> {
                ReadAccessMessage(
                    title = stringResource(R.string.discover_error_title),
                    body = stringResource(R.string.discover_error_body),
                )
                ReadAccessRetry(onRetry = onRetry)
            }
            AccessResolverState.Offline -> {
                ReadAccessMessage(
                    title = stringResource(R.string.discover_offline_title),
                    body = stringResource(R.string.discover_offline_body),
                )
                ReadAccessRetry(onRetry = onRetry)
            }
            is AccessResolverState.ConsentRequired -> {
                ReadAccessMessage(
                    title = stringResource(R.string.addon_consent_title),
                    body = stringResource(R.string.addon_consent_body, state.addonId),
                )
                NexoButton(
                    text = stringResource(R.string.addon_consent_allow),
                    onClick = onAllowConsent,
                    modifier = Modifier.fillMaxWidth(),
                )
                NexoButton(
                    text = stringResource(R.string.addon_consent_deny),
                    onClick = onDenyConsent,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Loaded: in-app PD download CTA (when gated) over the grouped web rows. */
@Composable
private fun ReadAccessLoaded(
    access: LegalAccess,
    download: DownloadImportState,
    onDownloadInApp: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    if (access.canDownloadInApp) {
        ReadAccessDownloadCta(
            download = download,
            onDownloadInApp = onDownloadInApp,
            onCancelDownload = onCancelDownload,
        )
        NexoDivider()
    }
    BookAccessList(
        access = access,
        onOpenExternal = { url -> uriHandler.openUri(url) },
    )
}

/** In-app download CTA: idle CTA, progress + cancel, success, failure retry. */
@Composable
private fun ReadAccessDownloadCta(
    download: DownloadImportState,
    onDownloadInApp: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    when (download) {
        is DownloadImportState.Idle ->
            NexoButton(
                text = stringResource(R.string.discover_download),
                onClick = onDownloadInApp,
                modifier = Modifier.fillMaxWidth(),
            )
        is DownloadImportState.Downloading, is DownloadImportState.Importing -> {
            NexoSkeletonBox(modifier = Modifier.fillMaxWidth())
            NexoButton(
                text = stringResource(R.string.discover_download_cancel),
                onClick = onCancelDownload,
                variant = NexoButtonVariant.OUTLINED,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is DownloadImportState.Success ->
            NexoButton(
                text = stringResource(R.string.discover_imported),
                onClick = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        is DownloadImportState.Duplicate ->
            Text(
                text = stringResource(R.string.discover_duplicate),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
            )
        is DownloadImportState.Failure -> {
            Text(
                text = stringResource(R.string.discover_download_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.errorSoft,
            )
            NexoButton(
                text = stringResource(R.string.discover_retry),
                onClick = onDownloadInApp,
                variant = NexoButtonVariant.OUTLINED,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Skeleton rows while the access section resolves. */
@Composable
private fun ReadAccessSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(READ_ACCESS_SKELETON_ROWS) {
            NexoSkeletonBox(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Centered title + body message for the empty/error/offline states. */
@Composable
private fun ReadAccessMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
        )
    }
}

/** Retry CTA shared by the error and offline states. */
@Composable
private fun ReadAccessRetry(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        NexoButton(
            text = stringResource(R.string.discover_retry),
            onClick = onRetry,
            variant = NexoButtonVariant.OUTLINED,
        )
    }
}

/** Skeleton row count for the loading state. */
private const val READ_ACCESS_SKELETON_ROWS = 3

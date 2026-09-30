package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.access.LegalAccess
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.components.atoms.NexoSkeletonBox

/**
 * Resolve states for one addon's "where to read" items (U5).
 *
 * [Resolving] covers the `resolveUrl` fetch; [Downloading] the gated
 * in-app download of a free addon item; [ConsentRequired] the pre-resolve
 * disclosure gate (zero I/O ran). UI-only states — the resolve itself
 * stays in [AddonCatalogProvider].
 */
sealed interface AddonReadState {
    /** Sheet hidden — the default; nothing is composed. */
    data object Hidden : AddonReadState

    data object Resolving : AddonReadState

    data object Downloading : AddonReadState

    /** Addon-resolved legal options for the open book. */
    data class Loaded(
        val access: LegalAccess,
    ) : AddonReadState

    data object Empty : AddonReadState

    data object Error : AddonReadState

    data object ConsentRequired : AddonReadState
}

/**
 * Addon items bottom sheet for the resolved book (U5): the addon's
 * external options plus the legal notice, with loading / empty /
 * downloading / error states.
 *
 * Consent gating is explicit: [AddonReadState.ConsentRequired] renders the
 * disclosure copy with allow/deny actions ([onAllowConsent] records the
 * U4 consent durably through the persistent store); every other state
 * renders only after consent exists. Links stay web-only external opens:
 * [onOpenExternal] only observes the open (e.g. analytics) — the sheet
 * always performs the external open itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonReadSheet(
    addonName: String,
    state: AddonReadState,
    onAllowConsent: () -> Unit,
    onDenyConsent: () -> Unit,
    onOpenExternal: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    if (state == AddonReadState.Hidden) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexoColors.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.discover_rail_from, addonName),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.textPrimary,
            )
            NexoDivider()
            when (state) {
                AddonReadState.Resolving, AddonReadState.Downloading -> {
                    repeat(ADDON_READ_SKELETON_ROWS) {
                        NexoSkeletonBox(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = stringResource(R.string.addon_read_resolving),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexoColors.textSecondary,
                    )
                }
                is AddonReadState.Loaded -> {
                    BookAccessList(
                        access = state.access,
                        onOpenExternal = { url ->
                            onOpenExternal(url)
                            uriHandler.openUri(url)
                        },
                    )
                    AddonLegalNotice()
                } AddonReadState.Empty -> {
                    Text(
                        text = stringResource(R.string.addon_read_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexoColors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.addon_read_empty_body, addonName),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexoColors.textSecondary,
                    )
                    AddonLegalNotice()
                }
                AddonReadState.Error -> {
                    Text(
                        text = stringResource(R.string.discover_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexoColors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.discover_error_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexoColors.textSecondary,
                    )
                    NexoButton(
                        text = stringResource(R.string.discover_retry),
                        onClick = onRetry,
                        variant = NexoButtonVariant.OUTLINED,
                    )
                }
                AddonReadState.Hidden -> Unit
                AddonReadState.ConsentRequired -> {
                    Text(
                        text = stringResource(R.string.addon_consent_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexoColors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.addon_consent_body, addonName),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexoColors.textSecondary,
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
                    AddonLegalNotice()
                }
            }
        }
    }
}

/** Legal notice pinned under addon content: external source, user verifies rights. */
@Composable
private fun AddonLegalNotice(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        NexoDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.addon_read_legal_notice),
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
        )
    }
}

/** Skeleton row count while resolving or downloading. */
private const val ADDON_READ_SKELETON_ROWS = 2

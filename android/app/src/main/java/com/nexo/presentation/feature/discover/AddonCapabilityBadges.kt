package com.nexo.presentation.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors

/**
 * Capability chips for an installed addon manifest (U5).
 *
 * Known capability ids (`search`, `details`, `resolve`) render localized
 * labels; unknown ids render raw so a manifest the app predates still
 * discloses exactly what it declares (never silently dropped).
 */
@Composable
fun AddonCapabilityBadges(
    capabilities: List<String>,
    modifier: Modifier = Modifier,
) {
    if (capabilities.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.addon_capabilities_title),
            style = MaterialTheme.typography.labelSmall,
            color = NexoColors.textSecondary,
        )
        DiscoverChipRow(
            chips = capabilities.map { DiscoverChip(label = capabilityLabel(it)) },
            onChipClick = { },
            chipBackground = NexoColors.surfaceVariant,
            chipLabelColor = NexoColors.textSecondary,
        )
    }
}

/**
 * Trust note shown under the capability chips: addons run outside NEXO,
 * so consent belongs only to sources the user trusts. Links to the full
 * legal page through [onViewPolicy].
 */
@Composable
fun AddonTrustNote(
    onViewPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.addon_consent_trust_note),
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.legal_disclaimer_view_policy),
            style = MaterialTheme.typography.labelMedium,
            color = NexoColors.textAccent,
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onViewPolicy),
        )
    }
}

/** Localized label for a known capability id; unknown ids render raw. */
@Composable
internal fun capabilityLabel(capability: String): String =
    when (capability.trim().lowercase()) {
        CAPABILITY_SEARCH -> stringResource(R.string.addon_capability_search)
        CAPABILITY_DETAILS -> stringResource(R.string.addon_capability_details)
        CAPABILITY_RESOLVE -> stringResource(R.string.addon_capability_resolve)
        else -> capability
    }

/** Manifest capability: the addon serves catalog search results. */
private const val CAPABILITY_SEARCH = "search"

/** Manifest capability: the addon serves per-book detail payloads. */
private const val CAPABILITY_DETAILS = "details"

/** Manifest capability: the addon resolves "where to read" links. */
private const val CAPABILITY_RESOLVE = "resolve"

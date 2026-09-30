package com.nexo.presentation.feature.legal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.InstalledAddonRow
import com.nexo.presentation.feature.discover.AddonCapabilityBadges
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.icons.NexoIcons

/** Builds the capability-detail route for [addonId] (registry ids are hex). */
fun addonCapabilitiesRoute(addonId: String): String = "settings/addon-capabilities/$addonId"

/**
 * Stateful host for [AddonCapabilityDetailScreen]: loads the installed row
 * once per [addonId] and mirrors the durable consent decision locally so
 * the switch reflects immediately.
 */
@Composable
fun AddonCapabilityDetailRoute(
    registry: AddonRegistryLike,
    addonId: String,
    hasConsent: (String) -> Boolean,
    onConsentChange: (String, Boolean) -> Unit,
    onViewPolicy: () -> Unit,
    onBack: () -> Unit,
) {
    var row by remember(addonId) { mutableStateOf<InstalledAddonRow?>(null) }
    var consented by remember(addonId) { mutableStateOf(hasConsent(addonId)) }
    LaunchedEffect(addonId) {
        row = registry.listInstalled().find { it.id == addonId }
    }
    val current = row
    if (current == null) {
        Text(
            text = stringResource(R.string.discover_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = NexoColors.textSecondary,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    AddonCapabilityDetailScreen(
        addonName = current.manifest.name,
        capabilities = current.manifest.capabilities,
        hasConsent = consented,
        onConsentChange = { granted ->
            onConsentChange(addonId, granted)
            consented = granted
        },
        onViewPolicy = onViewPolicy,
        onBack = onBack,
    )
}

/**
 * Per-addon capability detail (U5): what one installed addon declares it
 * can do, plus the trust note and the consent state.
 *
 * @param addonName Display name of the addon (from its manifest).
 * @param capabilities Declared v2 capability ids (raw; unknown ids render raw).
 * @param hasConsent True once the capability disclosure was consented.
 * @param onConsentChange Records or revokes disclosure consent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonCapabilityDetailScreen(
    addonName: String,
    capabilities: List<String>,
    hasConsent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onViewPolicy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.addon_capability_detail_title, addonName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.settings_addons_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.addon_capability_detail_body),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textPrimary,
            )
            AddonCapabilityBadges(capabilities = capabilities)
            androidx.compose.material3.Switch(
                checked = hasConsent,
                onCheckedChange = onConsentChange,
            )
            Text(
                text =
                    stringResource(
                        if (hasConsent) {
                            R.string.addon_consent_state_granted
                        } else {
                            R.string.addon_consent_state_missing
                        },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = NexoColors.textSecondary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.addon_consent_trust_note),
                style = MaterialTheme.typography.bodySmall,
                color = NexoColors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.legal_disclaimer_view_policy),
                style = MaterialTheme.typography.labelMedium,
                color = NexoColors.textAccent,
                modifier = Modifier.clickable(onClick = onViewPolicy),
            )
        }
    }
}

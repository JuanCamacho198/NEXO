package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.InstalledAddonRow
import com.nexo.presentation.feature.discover.AddonCapabilityBadges
import com.nexo.presentation.feature.discover.AddonTrustNote
import com.nexo.presentation.navigation.rememberAddonSettingsViewModel
import com.nexo.presentation.viewmodel.AddonSettingsUiState
import com.nexo.presentation.viewmodel.AddonSettingsViewModel
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant
import com.nexo.ui.components.atoms.NexoSnackbar
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

/**
 * Stateful host for [AddonManagementScreen].
 *
 * Owns the registry-backed [AddonSettingsViewModel] and the snackbar channel:
 * install errors surface through the ViewModel's `onError` callback and are
 * shown via [NexoSnackbar]. The registry is `refresh()`-ed exactly once per
 * composition entry.
 *
 * ViewModel resolution is single-sourced in
 * [com.nexo.presentation.navigation.rememberAddonSettingsViewModel], which
 * wraps the registry-backed factory; this route never calls `viewModel()`.
 */
@Composable
fun AddonManagementRoute(
    registry: AddonRegistryLike,
    onBack: () -> Unit,
    hasConsent: (String) -> Boolean = { false },
    onConsentChange: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateToLegal: () -> Unit = {},
    onOpenCapabilities: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val viewModel: AddonSettingsViewModel =
        rememberAddonSettingsViewModel(
            registry = registry,
            onError = { errorMessage = it },
            hasConsent = hasConsent,
            onConsentChange = onConsentChange,
        )

    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            errorMessage = null
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AddonManagementScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onUrlChange = viewModel::onUrlChange,
        onInstall = viewModel::install,
        onToggle = viewModel::toggle,
        onUninstall = viewModel::uninstall,
        onConsentChange = viewModel::setConsent,
        onNavigateToLegal = onNavigateToLegal,
        onOpenCapabilities = onOpenCapabilities,
        onBack = onBack,
    )
}

/** Factory wiring [AddonSettingsViewModel] to the app-scoped addon registry. */
class AddonSettingsViewModelFactory(
    private val registry: AddonRegistryLike,
    private val onError: (String) -> Unit,
    private val hasConsent: (String) -> Boolean = { false },
    private val onConsentChange: (String, Boolean) -> Unit = { _, _ -> },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddonSettingsViewModel::class.java)) {
            return AddonSettingsViewModel(registry, onError, hasConsent, onConsentChange) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * Stateless addon management surface: URL install field, installed-addon rows
 * with enable/disable switches, and a destructive uninstall confirm per row.
 * All controls are disabled while [AddonSettingsUiState.isBusy] is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonManagementScreen(
    uiState: AddonSettingsUiState,
    onUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onConsentChange: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateToLegal: () -> Unit = {},
    onOpenCapabilities: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_addons_title)) },
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
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                NexoSnackbar(snackbarData = data)
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_addons_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NexoTextField(
                value = uiState.url,
                onValueChange = onUrlChange,
                label = stringResource(R.string.settings_addons_url_label),
                placeholder = stringResource(R.string.settings_addons_url_placeholder),
                enabled = !uiState.isBusy,
            )

            NexoButton(
                text =
                    stringResource(
                        if (uiState.isBusy) {
                            R.string.settings_addons_installing
                        } else {
                            R.string.settings_addons_install
                        },
                    ),
                onClick = onInstall,
                enabled = !uiState.isBusy && uiState.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            BuiltInSourcesSection()

            if (uiState.installed.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_addons_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.installed, key = { it.id }) { row ->
                        AddonRow(
                            row = row,
                            isBusy = uiState.isBusy,
                            hasConsent = row.id in uiState.consentedIds,
                            onToggle = onToggle,
                            onUninstall = onUninstall,
                            onConsentChange = onConsentChange,
                            onOpenCapabilities = onOpenCapabilities,
                        )
                    }
                }
            }

            AddonTrustNote(onViewPolicy = onNavigateToLegal)
        }
    }
}

/**
 * U4: read-only built-in sources section, rendered above managed addons.
 *
 * Zero state writes; rows are plain Cards with no clickable, toggle, Switch,
 * or onClick on any row.
 */
@Composable
fun BuiltInSourcesSection(
    modifier: Modifier = Modifier,
    sources: List<BuiltInSource> = builtInSources,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_addons_builtin_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        sources.forEach { source ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(source.roleResId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    row: InstalledAddonRow,
    isBusy: Boolean,
    hasConsent: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onConsentChange: (String, Boolean) -> Unit,
    onOpenCapabilities: (String) -> Unit,
) {
    var showUninstallDialog by remember { mutableStateOf(false) }
    val toggleDescription =
        stringResource(
            if (row.enabled) R.string.settings_addons_disable else R.string.settings_addons_enable,
        )
    val stateCaption =
        stringResource(
            if (row.enabled) R.string.settings_addons_state_enabled else R.string.settings_addons_state_disabled,
        )
    val consentCaption =
        stringResource(
            if (hasConsent) {
                R.string.addon_consent_state_granted
            } else {
                R.string.addon_consent_state_missing
            },
        )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.manifest.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "v${row.manifest.version} \u00B7 $stateCaption",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Switch(
                    checked = row.enabled,
                    onCheckedChange = { checked -> onToggle(row.id, checked) },
                    enabled = !isBusy,
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                )

                Spacer(Modifier.width(8.dp))

                TextButton(
                    onClick = { showUninstallDialog = true },
                    enabled = !isBusy,
                ) {
                    Text(
                        text = stringResource(R.string.settings_addons_uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            AddonCapabilityBadges(capabilities = row.manifest.capabilities)

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = hasConsent,
                    onCheckedChange = { granted -> onConsentChange(row.id, granted) },
                    enabled = !isBusy,
                    modifier = Modifier.semantics { contentDescription = consentCaption },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = consentCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onOpenCapabilities(row.id) },
                    enabled = !isBusy,
                ) {
                    Text(text = stringResource(R.string.addon_capabilities_title))
                }
            }
        }
    }

    if (showUninstallDialog) {
        NexoDialog(
            title = stringResource(R.string.settings_addons_uninstall_confirm),
            body = stringResource(R.string.settings_addons_uninstall_confirm_body),
            confirmText = stringResource(R.string.settings_addons_uninstall_confirm_action),
            dismissText = stringResource(R.string.settings_addons_uninstall_confirm_cancel),
            onConfirm = {
                onUninstall(row.id)
                showUninstallDialog = false
            },
            onDismiss = { showUninstallDialog = false },
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

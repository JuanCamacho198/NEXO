package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Device
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.SettingsDevicesUiState
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant
import com.nexo.ui.icons.NexoIcons
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDevicesScreen(
    uiState: SettingsDevicesUiState,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NexoIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null -> {
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.settings_devices_count, uiState.deviceCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        items(uiState.devices, key = { it.id }) { device ->
                            DeviceItem(
                                device = device,
                                isCurrent = device.id == uiState.currentDeviceId,
                                onRemove = { onRemove(device.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: Device,
    isCurrent: Boolean,
    onRemove: () -> Unit,
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    when {
                        device.os.contains("Android") -> NexoIcons.Smartphone
                        device.os.contains("Windows") -> NexoIcons.Monitor
                        device.os.contains("macOS") || device.os.contains("Darwin") -> NexoIcons.Laptop
                        else -> NexoIcons.Devices
                    },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.settings_devices_this_device),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Text(
                    text = "${device.os} \u00B7 ${formatRelativeTime(device.lastActive)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isCurrent) {
                TextButton(onClick = { showRemoveDialog = true }) {
                    Text(
                        stringResource(R.string.settings_devices_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showRemoveDialog) {
        NexoDialog(
            title = stringResource(R.string.settings_devices_remove_confirm, device.name),
            body = "",
            confirmText = stringResource(R.string.settings_devices_remove),
            dismissText = stringResource(android.R.string.cancel),
            onConfirm = {
                onRemove()
                showRemoveDialog = false
            },
            onDismiss = { showRemoveDialog = false },
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

private fun formatRelativeTime(lastActive: String): String =
    try {
        val instant = Instant.parse(lastActive)
        val duration = Duration.between(instant, Instant.now())
        when {
            duration.toMinutes() < 1 -> "now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()} min ago"
            duration.toHours() < 24 -> "${duration.toHours()}h ago"
            else -> "${duration.toDays()}d ago"
        }
    } catch (e: Exception) {
        lastActive
    }

// ─── Previews ─────────────────────────────────────────────────────────

private val PreviewDevices =
    listOf(
        Device(
            id = "device-1",
            name = "Pixel 8",
            os = "Android 15",
            lastActive = "2024-01-01T00:00:00Z",
        ),
        Device(
            id = "device-2",
            name = "Work Laptop",
            os = "Windows 11",
            lastActive = "2024-01-01T00:00:00Z",
        ),
    )

@Preview(showBackground = true)
@Composable
private fun SettingsDevicesScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsDevicesScreen(
            uiState =
                SettingsDevicesUiState(
                    devices = PreviewDevices,
                    currentDeviceId = "device-1",
                    isLoading = false,
                    errorMessage = null,
                    deviceCount = PreviewDevices.size,
                ),
            onRemove = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsDevicesScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsDevicesScreen(
            uiState =
                SettingsDevicesUiState(
                    devices = PreviewDevices,
                    currentDeviceId = "device-1",
                    isLoading = false,
                    errorMessage = null,
                    deviceCount = PreviewDevices.size,
                ),
            onRemove = {},
            onBack = {},
        )
    }
}

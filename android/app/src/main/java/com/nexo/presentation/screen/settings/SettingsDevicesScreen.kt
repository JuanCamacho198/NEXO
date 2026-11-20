package com.nexo.presentation.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Device
import com.nexo.feature.settings.R
import com.nexo.presentation.viewmodel.SettingsDevicesUiState
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors
import java.time.Duration
import java.time.Instant

@Composable
fun SettingsDevicesScreen(
    uiState: SettingsDevicesUiState,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.settings_devices_title),
        onBack = onBack,
        backContentDescription = stringResource(com.nexo.R.string.nav_back),
    ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.errorMessage != null -> {
                Text(
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            else -> {
                val current = uiState.devices.firstOrNull { it.id == uiState.currentDeviceId }
                val others = uiState.devices.filter { it.id != uiState.currentDeviceId }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (current != null) {
                        item(key = "current") { CurrentDeviceCard(device = current) }
                    }
                    if (others.isNotEmpty()) {
                        item(key = "others") {
                            NexoSettingsSection(
                                title = stringResource(R.string.settings_devices_count, uiState.deviceCount),
                            ) {
                                others.forEachIndexed { index, device ->
                                    if (index > 0) NexoSettingsDivider()
                                    OtherDeviceRow(device = device, onRemove = { onRemove(device.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun deviceIcon(os: String): ImageVector =
    when {
        os.contains("Android") -> NexoIcons.Smartphone
        os.contains("Windows") -> NexoIcons.Monitor
        os.contains("macOS") || os.contains("Darwin") -> NexoIcons.Laptop
        else -> NexoIcons.Devices
    }

/** The glyph is the only thing that says WHICH platform a row is, so it carries a label. */
@Composable
private fun deviceIconDescription(os: String): String =
    stringResource(
        when {
            os.contains("Android") -> com.nexo.R.string.a11y_device_android
            os.contains("Windows") -> com.nexo.R.string.a11y_device_windows
            os.contains("macOS") || os.contains("Darwin") -> com.nexo.R.string.a11y_device_apple
            else -> com.nexo.R.string.a11y_device_other
        },
    )

/**
 * The device in hand, set apart from the list: a tinted card with a large
 * platform glyph and a green "this device" pill. It cannot be signed out from
 * here, so it carries no action.
 */
@Composable
private fun CurrentDeviceCard(device: Device) {
    val colors = nexoColors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.20f), colors.surface)))
                .border(1.dp, colors.borderSubtle, shape)
                .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = deviceIcon(device.os),
                contentDescription = deviceIconDescription(device.os),
                tint = colors.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = deviceMeta(device),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Row(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(colors.accentGreen.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.accentGreen))
                Text(
                    text = stringResource(R.string.settings_devices_this_device),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentGreen,
                )
            }
        }
    }
}

@Composable
private fun OtherDeviceRow(
    device: Device,
    onRemove: () -> Unit,
) {
    var showRemoveDialog by remember { mutableStateOf(false) }
    val colors = nexoColors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentPurple.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = deviceIcon(device.os),
                contentDescription = deviceIconDescription(device.os),
                tint = colors.accentPurple,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = deviceMeta(device),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        TextButton(onClick = { showRemoveDialog = true }) {
            Text(
                stringResource(R.string.settings_devices_remove),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (showRemoveDialog) {
        NexoDialog(
            title = stringResource(R.string.settings_devices_remove_confirm, device.name),
            body = "",
            confirmText = stringResource(R.string.settings_devices_remove),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemove()
                showRemoveDialog = false
            },
            onDismiss = { showRemoveDialog = false },
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

/** "Android 15 · 9d ago": platform and last activity, shown under the device name. */
private fun deviceMeta(device: Device): String = listOf(device.os, formatRelativeTime(device.lastActive)).joinToString(" · ")

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

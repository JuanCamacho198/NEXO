package com.nexo.presentation.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.notification.QuietHoursSchedule
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.components.molecules.NexoSettingsSwitchRow
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Real notification settings (U9).
 *
 * Reads the persisted [NotificationPreferences] on first composition and writes
 * every change back through the [NotificationPreferencesStore], so the switches
 * survive a restart and actually gate the delivery policy (the store's current
 * value is what `NotificationStore.emit` feeds the policy).
 *
 * @param preferencesStore Persisted preferences; `null` (e.g. a preview or a
 *   graph wired without the store) degrades to in-memory defaults so the screen
 *   still renders.
 */
@Composable
fun SettingsNotificationsScreen(
    onBack: () -> Unit,
    preferencesStore: NotificationPreferencesStore? = null,
) {
    var preferences by remember { mutableStateOf(preferencesStore?.load() ?: NotificationPreferences.DEFAULT) }

    fun update(next: NotificationPreferences) {
        preferences = next
        preferencesStore?.save(next)
    }

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_notifications_title),
        onBack = onBack,
    ) {
        val colors = nexoColors
        NexoSettingsSection {
            NexoSettingsSwitchRow(
                label = stringResource(R.string.settings_notifications_system_label),
                supporting = stringResource(R.string.settings_notifications_system_description),
                icon = NexoIcons.Notifications,
                checked = preferences.system,
                onCheckedChange = { update(preferences.copy(system = it)) },
            )
            NexoSettingsDivider()
            NexoSettingsSwitchRow(
                label = stringResource(R.string.settings_notifications_nudge_label),
                supporting = stringResource(R.string.settings_notifications_nudge_description),
                icon = NexoIcons.Sparkle,
                accent = colors.accentOrange,
                checked = preferences.nudge,
                onCheckedChange = { update(preferences.copy(nudge = it)) },
            )
            NexoSettingsDivider()
            NexoSettingsSwitchRow(
                label = stringResource(R.string.settings_notifications_quiet_hours_label),
                supporting = stringResource(R.string.settings_notifications_quiet_hours_description),
                icon = NexoIcons.DarkMode,
                accent = colors.accentPurple,
                checked = preferences.quietHours.enabled,
                onCheckedChange = { enabled ->
                    update(preferences.copy(quietHours = preferences.quietHours.copy(enabled = enabled)))
                },
            )
            // The schedule unfolds inside the same card, under the switch that owns it.
            AnimatedVisibility(
                visible = preferences.quietHours.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    NexoSettingsDivider()
                    Box(modifier = Modifier.padding(start = 62.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)) {
                        QuietHoursRow(
                            schedule = preferences.quietHours,
                            onStartChange = { start ->
                                update(preferences.copy(quietHours = preferences.quietHours.copy(start = start)))
                            },
                            onEndChange = { end ->
                                update(preferences.copy(quietHours = preferences.quietHours.copy(end = end)))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuietHoursRow(
    schedule: QuietHoursSchedule,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TimeField(
            label = stringResource(R.string.settings_notifications_quiet_hours_start),
            value = schedule.start,
            onValueChange = onStartChange,
            modifier = Modifier.weight(1f),
        )
        TimeField(
            label = stringResource(R.string.settings_notifications_quiet_hours_end),
            value = schedule.end,
            onValueChange = onEndChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() || it == ':' }.take(MAX_TIME_LENGTH)) },
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions =
            androidx.compose.foundation.text
                .KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val MAX_TIME_LENGTH = 5

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsNotificationsScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsNotificationsScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsNotificationsScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsNotificationsScreen(onBack = {})
    }
}

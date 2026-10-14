package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.notification.QuietHoursSchedule
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.NexoSettingsSubPage

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
        NotificationToggleRow(
            label = stringResource(R.string.settings_notifications_system_label),
            description = stringResource(R.string.settings_notifications_system_description),
            checked = preferences.system,
            onCheckedChange = { update(preferences.copy(system = it)) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        NotificationToggleRow(
            label = stringResource(R.string.settings_notifications_nudge_label),
            description = stringResource(R.string.settings_notifications_nudge_description),
            checked = preferences.nudge,
            onCheckedChange = { update(preferences.copy(nudge = it)) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        NotificationToggleRow(
            label = stringResource(R.string.settings_notifications_quiet_hours_label),
            description = stringResource(R.string.settings_notifications_quiet_hours_description),
            checked = preferences.quietHours.enabled,
            onCheckedChange = { enabled ->
                update(preferences.copy(quietHours = preferences.quietHours.copy(enabled = enabled)))
            },
        )

        if (preferences.quietHours.enabled) {
            Spacer(modifier = Modifier.height(8.dp))
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

@Composable
private fun NotificationToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
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
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
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

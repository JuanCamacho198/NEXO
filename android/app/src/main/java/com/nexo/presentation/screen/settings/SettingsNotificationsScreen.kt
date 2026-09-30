package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.NexoSettingsSubPage

@Composable
fun SettingsNotificationsScreen(
    onBack: () -> Unit,
) {
    var readingReminders by remember { mutableStateOf(true) }
    var streakAlerts by remember { mutableStateOf(true) }
    var newBookAlerts by remember { mutableStateOf(false) }

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_notifications_title),
        onBack = onBack,
    ) {
        NotificationToggleRow(
            label = stringResource(R.string.notifications_mock_streak_title),
            checked = readingReminders,
            onCheckedChange = { readingReminders = it },
        )

        Spacer(modifier = Modifier.height(8.dp))

        NotificationToggleRow(
            label = stringResource(R.string.statistics_current_streak),
            checked = streakAlerts,
            onCheckedChange = { streakAlerts = it },
        )

        Spacer(modifier = Modifier.height(8.dp))

        NotificationToggleRow(
            label = stringResource(R.string.notifications_mock_library_title),
            checked = newBookAlerts,
            onCheckedChange = { newBookAlerts = it },
        )
    }
}

@Composable
private fun NotificationToggleRow(
    label: String,
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

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

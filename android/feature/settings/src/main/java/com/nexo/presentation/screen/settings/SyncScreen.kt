package com.nexo.presentation.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsIcon
import com.nexo.ui.components.molecules.NexoSettingsNavRow
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

@Composable
fun SyncScreen(
    onBack: () -> Unit,
    onViewLogs: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Resolved in composition: `context.getString` inside a click lambda is what
    // `LocalContextGetResourceValueCall` flags. A Toast fires outside composition,
    // so the string is read here and captured by the lambda instead.
    val syncNowSuccess = stringResource(R.string.sync_now_success)
    val logViewerLabel = stringResource(R.string.debug_settings_log_viewer)
    val recentEntries =
        listOf(
            R.string.sync_entry_highlights to "14:30",
            R.string.sync_entry_progress to "14:28",
            R.string.sync_entry_book to "13:55",
            R.string.sync_entry_highlights to "12:40",
            R.string.sync_entry_progress to "11:15",
        )

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_sync_title),
        onBack = onBack,
    ) {
        val colors = nexoColors
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Status hero: realtime state on a tinted card, then the two figures.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.20f), colors.surface)))
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NexoSettingsIcon(icon = NexoIcons.CloudSync)
                    Text(
                        text = stringResource(R.string.sync_card_realtime_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(colors.accentGreen.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.accentGreen))
                        Text(
                            text = stringResource(R.string.sync_realtime_connected),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accentGreen,
                        )
                    }
                }
                SyncFact(label = stringResource(R.string.sync_last_sync_label), value = stringResource(R.string.sync_last_sync_value))
                SyncFact(label = stringResource(R.string.sync_outbox_label), value = stringResource(R.string.sync_outbox_value))
            }

            NexoSettingsSection(title = stringResource(R.string.sync_recent_title)) {
                recentEntries.forEachIndexed { index, (labelRes, time) ->
                    if (index > 0) NexoSettingsDivider()
                    NexoSettingsNavRow(
                        label = stringResource(labelRes),
                        icon = NexoIcons.Sync,
                        accent = colors.accentGreen,
                        onClick = null,
                        value = time,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        Toast.makeText(context, syncNowSuccess, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = NexoIcons.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.sync_action_sync_now), fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        if (onViewLogs != null) {
                            onViewLogs()
                        } else {
                            Toast.makeText(context, logViewerLabel, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(text = stringResource(R.string.sync_action_view_logs), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SyncFact(
    label: String,
    value: String,
) {
    val colors = nexoColors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SyncScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SyncScreen(onBack = {})
    }
}

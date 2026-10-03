package com.nexo.presentation.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.BuildConfig
import com.nexo.R
import com.nexo.data.session.AppLanguagePreferences
import com.nexo.debug.DebugPrefs
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.ThemeMode
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.components.molecules.NexoHeader
import com.nexo.ui.components.molecules.NexoPreferenceItem
import com.nexo.ui.icons.NexoIcons

private data class SettingsRow(
    val labelRes: Int,
    val icon: ImageVector,
    val value: String? = null,
    val onClick: () -> Unit = {},
)

private data class SettingsGroup(
    val titleRes: Int,
    val rows: List<SettingsRow>,
)

@Composable
fun SettingsListScreen(
    authSession: AuthSession?,
    appThemeMode: ThemeMode,
    readingGoalPreferences: com.nexo.data.session.ReadingGoalPreferences? = null,
    onNavigateToAccount: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToPalette: () -> Unit,
    onNavigateToDataStorage: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDictionary: () -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
    onNavigateToDailyGoal: () -> Unit = {},
    onNavigateToPerformance: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToStorage: () -> Unit = onNavigateToDataStorage,
    onNavigateToSync: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val langPrefs = androidx.compose.runtime.remember { AppLanguagePreferences(context = context) }

    // FR-AD1: the persisted toggle is the sole authority for debug tools.
    var debugEnabled by remember { mutableStateOf(DebugPrefs.isEnabled(context)) }

    val currentLanguageLabel =
        when (langPrefs.load()) {
            "es" -> R.string.settings_language_spanish
            "en" -> R.string.settings_language_english
            else -> R.string.settings_language_system
        }

    val currentThemeLabel =
        when (appThemeMode) {
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
            ThemeMode.SYSTEM -> R.string.settings_theme_system
        }

    val currentGoalValue =
        readingGoalPreferences?.load()?.let { minutes ->
            when (minutes) {
                10 -> stringResource(R.string.onboarding_goal_option_relaxed_value)
                20 -> stringResource(R.string.onboarding_goal_option_regular_value)
                30 -> stringResource(R.string.onboarding_goal_option_serious_value)
                45 -> stringResource(R.string.onboarding_goal_option_intense_value)
                else -> "$minutes min/día"
            }
        }

    val groups =
        listOf(
            SettingsGroup(
                titleRes = R.string.settings_account_section,
                rows =
                    listOf(
                        SettingsRow(
                            labelRes = R.string.settings_account_title,
                            icon = NexoIcons.Person,
                            onClick = onNavigateToAccount,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_devices_title,
                            icon = NexoIcons.Devices,
                            onClick = onNavigateToDevices,
                        ),
                    ),
            ),
            SettingsGroup(
                titleRes = R.string.settings_apariencia_section,
                rows =
                    listOf(
                        SettingsRow(
                            labelRes = R.string.settings_pref_theme,
                            icon = NexoIcons.DarkMode,
                            value = stringResource(currentThemeLabel),
                            onClick = onNavigateToTheme,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_pref_language,
                            icon = NexoIcons.Language,
                            value = stringResource(currentLanguageLabel),
                            onClick = onNavigateToLanguage,
                        ),
                        SettingsRow(
                            labelRes = R.string.palette_section_title,
                            icon = NexoIcons.Palette,
                            onClick = onNavigateToPalette,
                        ),
                    ),
            ),
            SettingsGroup(
                titleRes = R.string.settings_lectura_section,
                rows =
                    listOf(
                        SettingsRow(
                            labelRes = R.string.settings_daily_goal_title,
                            icon = NexoIcons.Clock,
                            value = currentGoalValue,
                            onClick = onNavigateToDailyGoal,
                        ),
                    ),
            ),
            SettingsGroup(
                titleRes = R.string.settings_datos_section,
                rows =
                    listOf(
                        SettingsRow(
                            labelRes = R.string.settings_storage_title,
                            icon = NexoIcons.Storage,
                            onClick = onNavigateToStorage,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_sync_title,
                            icon = NexoIcons.Sync,
                            onClick = onNavigateToSync,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_data_storage_title,
                            icon = NexoIcons.CloudSync,
                            onClick = onNavigateToDataStorage,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_pref_notifications,
                            icon = NexoIcons.Notifications,
                            onClick = onNavigateToNotifications,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_dictionary_label,
                            icon = NexoIcons.LibraryBooks,
                            onClick = onNavigateToDictionary,
                        ),
                    ) +
                        if (BuildConfig.DEBUG) {
                            listOf(
                                SettingsRow(
                                    labelRes = R.string.settings_performance_title,
                                    icon = NexoIcons.Performance,
                                    onClick = onNavigateToPerformance,
                                ),
                            )
                        } else {
                            emptyList()
                        },
            ),
            SettingsGroup(
                titleRes = R.string.settings_info_section,
                rows =
                    listOf(
                        SettingsRow(
                            labelRes = R.string.settings_pref_about,
                            icon = NexoIcons.Info,
                            onClick = onNavigateToAbout,
                        ),
                    ),
            ),
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            NexoHeader(
                title = stringResource(R.string.home_nexo_title),
                avatarInitials = stringResource(R.string.app_logo_initials),
            )

            TitleSection()

            AccountSection(
                authSession = authSession,
                onClick = onNavigateToAccount,
            )

            groups.forEach { group ->
                SettingsGroupBlock(group = group)
            }

            // PP-3: privacy notice + telemetry opt-out (stops SENDING; the
            // Android DSN is compile-time, so this vetoes at the SDK hooks).
            PrivacyTelemetrySection(context = context)

            DebugModeSection(
                debugEnabled = debugEnabled,
                onToggle = { enabled ->
                    debugEnabled = enabled
                    DebugPrefs.setEnabled(context, enabled)
                },
            )

            if (debugEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.debug_panel_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    NexoPreferenceItem(
                        icon = NexoIcons.BugReport,
                        label = stringResource(R.string.debug_settings_log_viewer),
                        onClick = onNavigateToLogViewer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TitleSection() {
    Column {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountSection(
    authSession: AuthSession?,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_account_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NexoAvatar(
                    imageUrl = authSession?.photoUrl,
                    initials =
                        (authSession?.displayName ?: stringResource(R.string.settings_user_default))
                            .take(2)
                            .uppercase(),
                    size = 48.dp,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authSession?.displayName ?: stringResource(R.string.settings_user_default),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = authSession?.email ?: stringResource(R.string.settings_email_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupBlock(group: SettingsGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(group.titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        group.rows.forEach { row ->
            NexoPreferenceItem(
                icon = row.icon,
                label = stringResource(row.labelRes),
                value = row.value,
                onClick = row.onClick,
            )
        }
    }
}

@Composable
private fun DebugModeSection(
    debugEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.debug_mode_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.debug_mode_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = debugEnabled,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsListScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsListScreen(
            authSession = null,
            appThemeMode = ThemeMode.SYSTEM,
            onNavigateToAccount = {},
            onNavigateToTheme = {},
            onNavigateToLanguage = {},
            onNavigateToPalette = {},
            onNavigateToDataStorage = {},
            onNavigateToNotifications = {},
            onNavigateToAbout = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsListScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsListScreen(
            authSession = null,
            appThemeMode = ThemeMode.SYSTEM,
            onNavigateToAccount = {},
            onNavigateToTheme = {},
            onNavigateToLanguage = {},
            onNavigateToPalette = {},
            onNavigateToDataStorage = {},
            onNavigateToNotifications = {},
            onNavigateToAbout = {},
        )
    }
}

@Composable
private fun PrivacyTelemetrySection(context: android.content.Context) {
    var enabled by remember {
        mutableStateOf(
            com.nexo.debug.SentryPrivacyPrefs
                .isEnabled(context),
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_privacy_collected),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.settings_privacy_never),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_privacy_toggle_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        com.nexo.debug.SentryPrivacyPrefs
                            .setEnabled(context, checked)
                    },
                )
            }
            Text(
                text =
                    if (enabled) {
                        stringResource(R.string.settings_privacy_toggle_hint_on)
                    } else {
                        stringResource(R.string.settings_privacy_toggle_hint_off)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

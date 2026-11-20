package com.nexo.presentation.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.data.session.AppLanguagePreferences
import com.nexo.debug.DebugPrefs
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.ThemeMode
import com.nexo.feature.settings.BuildConfig
import com.nexo.feature.settings.R
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

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
    onNavigateToTranslation: () -> Unit = {},
    onNavigateToDefinition: () -> Unit = {},
    onNavigateToPerformance: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToStorage: () -> Unit = onNavigateToDataStorage,
    onNavigateToSync: () -> Unit = {},
    onNavigateToAddons: () -> Unit = {},
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
                        SettingsRow(
                            labelRes = R.string.settings_translation_title,
                            icon = NexoIcons.Translate,
                            onClick = onNavigateToTranslation,
                        ),
                        SettingsRow(
                            labelRes = R.string.settings_definition_title,
                            icon = NexoIcons.BookSearch,
                            onClick = onNavigateToDefinition,
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
                        SettingsRow(
                            labelRes = R.string.settings_manage_addons,
                            icon = NexoIcons.GridView,
                            onClick = onNavigateToAddons,
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

    val entrance = rememberEntranceProgress()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        TitleSection()

        ProfileCard(
            authSession = authSession,
            onClick = onNavigateToAccount,
            modifier = Modifier.staggeredEnter(entrance, 0),
        )

        groups.forEachIndexed { index, group ->
            SettingsGroupBlock(
                group = group,
                accent = groupAccent(index),
                modifier = Modifier.staggeredEnter(entrance, index + 1),
            )
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
            onNavigateToLogViewer = onNavigateToLogViewer,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Icon tint per settings group, in group order, so each block reads as its own
 * family (account, appearance, reading, data, info) instead of five identical
 * stacks of grey rows.
 */
@Composable
private fun groupAccent(index: Int): Color {
    val colors = nexoColors
    return when (index) {
        0 -> colors.primary
        1 -> colors.accentPurple
        2 -> colors.accentOrange
        3 -> colors.accentGreen
        else -> colors.accentBlue
    }
}

private val GroupShape = RoundedCornerShape(18.dp)
private val RowMinHeight = 56.dp
private val RowIconBox = 34.dp

/** Divider inset: past the icon box, so rows read as one list per group. */
private val DividerInset = 62.dp

@Composable
private fun TitleSection() {
    Column {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = nexoColors.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = nexoColors.textSecondary,
        )
    }
}

/**
 * The signed-in reader, first: a tinted card with avatar, name and email that
 * opens the account page. Replaces both the "NEXO" brand bar and the duplicated
 * "Account" heading that used to sit above a plain grey card.
 */
@Composable
private fun ProfileCard(
    authSession: AuthSession?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val name = authSession?.displayName ?: stringResource(R.string.settings_user_default)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .pressScaleClickable(shape = GroupShape, onClick = onClick)
                .background(Brush.linearGradient(listOf(colors.accentOrange.copy(alpha = 0.20f), colors.surface)))
                .border(1.dp, colors.borderSubtle, GroupShape)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NexoAvatar(
            imageUrl = authSession?.photoUrl,
            initials = name.take(2).uppercase(),
            size = 56.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = authSession?.email ?: stringResource(R.string.settings_email_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(imageVector = NexoIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}

/** A labelled group: small heading, then every row inside ONE card with inset dividers. */
@Composable
private fun SettingsGroupBlock(
    group: SettingsGroup,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupHeading(text = stringResource(group.titleRes))
        GroupCard {
            group.rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                SettingsRowItem(row = row, accent = accent)
            }
        }
    }
}

@Composable
private fun GroupHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = nexoColors.textSecondary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    val colors = nexoColors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, GroupShape),
    ) {
        content()
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = DividerInset)
                .height(1.dp)
                .background(nexoColors.borderSubtle),
    )
}

@Composable
private fun RowIcon(
    icon: ImageVector,
    accent: Color,
) {
    Box(
        modifier =
            Modifier
                .size(RowIconBox)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsRowItem(
    row: SettingsRow,
    accent: Color,
) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .clickable(onClick = row.onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(icon = row.icon, accent = accent)
        Text(
            text = stringResource(row.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (!row.value.isNullOrBlank()) {
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(imageVector = NexoIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DebugModeSection(
    debugEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onNavigateToLogViewer: () -> Unit,
) {
    val colors = nexoColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupHeading(text = stringResource(R.string.debug_mode_title))
        GroupCard {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RowMinHeight)
                        .toggleable(value = debugEnabled, role = Role.Switch, onValueChange = onToggle)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RowIcon(icon = NexoIcons.BugReport, accent = colors.textSecondary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.debug_mode_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                // The whole row toggles; the switch only mirrors the state.
                Switch(checked = debugEnabled, onCheckedChange = null)
            }
            AnimatedVisibility(visible = debugEnabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    RowDivider()
                    SettingsRowItem(
                        row = SettingsRow(labelRes = R.string.debug_settings_log_viewer, icon = NexoIcons.BugReport, onClick = onNavigateToLogViewer),
                        accent = colors.textSecondary,
                    )
                }
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
    val colors = nexoColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupHeading(text = stringResource(R.string.settings_privacy_title))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(GroupShape)
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, GroupShape)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_collected),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Text(
                text = stringResource(R.string.settings_privacy_never),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = enabled,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                enabled = checked
                                com.nexo.debug.SentryPrivacyPrefs
                                    .setEnabled(context, checked)
                            },
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_privacy_toggle_label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = null)
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

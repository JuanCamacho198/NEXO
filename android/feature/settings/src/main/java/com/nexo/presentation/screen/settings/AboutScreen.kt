package com.nexo.presentation.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.domain.update.UpdateErrorKind
import com.nexo.domain.update.UpdateUiState
import com.nexo.feature.settings.BuildConfig
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
fun AboutScreen(
    onBack: () -> Unit,
    onTerms: (() -> Unit)? = null,
    onPrivacy: (() -> Unit)? = null,
    onLicenses: (() -> Unit)? = null,
    onContact: (() -> Unit)? = null,
    onRate: (() -> Unit)? = null,
    isUpdateCheckEnabled: Boolean = false,
    updateState: UpdateUiState = UpdateUiState.Idle,
    onCheckUpdates: () -> Unit = {},
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.settings_about_title),
        onBack = onBack,
    ) {
        val colors = nexoColors
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App identity: mark, name, version and build, centred.
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(colors.primary, colors.accentPurple))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = NexoIcons.BookOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME) + "  ·  " + stringResource(R.string.settings_about_build, BuildConfig.GIT_SHA),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // The quote, set as a pull-quote: accent mark, larger serif-less type.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(imageVector = NexoIcons.Quote, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.about_lincoln_quote),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
                    fontStyle = FontStyle.Italic,
                    color = colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.about_lincoln_author),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
            }

            // SDD app-auto-update: manual check row below the version block.
            // Rendered only when a feed URL is configured; empty URL keeps
            // version-display-only behavior.
            if (isUpdateCheckEnabled) {
                UpdateCheckRow(state = updateState, onCheckUpdates = onCheckUpdates)
            }

            NexoSettingsSection {
                AboutRow(label = stringResource(R.string.about_terms), icon = NexoIcons.Info, onClick = onTerms)
                NexoSettingsDivider()
                AboutRow(label = stringResource(R.string.about_privacy), icon = NexoIcons.Lock, onClick = onPrivacy)
                NexoSettingsDivider()
                AboutRow(label = stringResource(R.string.about_licenses), icon = NexoIcons.LibraryBooks, onClick = onLicenses)
                NexoSettingsDivider()
                AboutRow(label = stringResource(R.string.about_contact), icon = NexoIcons.Email, onClick = onContact)
                NexoSettingsDivider()
                AboutRow(label = stringResource(R.string.about_rate), icon = NexoIcons.Star, onClick = onRate)
            }

            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UpdateCheckRow(
    state: UpdateUiState,
    onCheckUpdates: () -> Unit,
) {
    val statusText =
        when (state) {
            is UpdateUiState.Checking -> stringResource(R.string.update_checking)
            is UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
            is UpdateUiState.Available ->
                stringResource(R.string.update_available_body, state.candidate.version)
            is UpdateUiState.Error ->
                when (state.kind) {
                    UpdateErrorKind.OFFLINE -> stringResource(R.string.update_error_offline)
                    UpdateErrorKind.MALFORMED -> stringResource(R.string.update_error_malformed)
                    UpdateErrorKind.UNREACHABLE -> stringResource(R.string.update_error_unreachable)
                    UpdateErrorKind.MANAGED_INSTALL -> stringResource(R.string.update_error_managed_install)
                }
            is UpdateUiState.Idle -> null
        }
    NexoSettingsSection {
        NexoSettingsNavRow(
            label = stringResource(R.string.update_check),
            supporting = statusText,
            icon = NexoIcons.CloudDownload,
            accent = if (state is UpdateUiState.Available) nexoColors.accentGreen else nexoColors.primary,
            onClick = onCheckUpdates,
            trailing =
                if (state is UpdateUiState.Checking) {
                    { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
        )
    }
}

/**
 * Pressable only when a handler exists.
 *
 * A null handler leaves the row inert: no `clickable`, so Compose emits no
 * click action and no `Role.Button`, and TalkBack stops announcing a target
 * that cannot be activated.
 */
private fun Modifier.aboutRowClickable(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) {
        this
    } else {
        clickable(role = Role.Button, onClick = onClick)
    }

/** One link row inside the About card: icon tile, label, chevron when it opens something. */
@Composable
internal fun AboutRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .aboutRowClickable(onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NexoSettingsIcon(icon = icon, accent = if (onClick != null) colors.primary else colors.textSecondary)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // Only a row that actually opens something earns the chevron.
        if (onClick != null) {
            Icon(
                imageVector = NexoIcons.ChevronRight,
                contentDescription = stringResource(R.string.about_row_opens_screen),
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        AboutScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        AboutScreen(onBack = {})
    }
}

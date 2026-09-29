package com.nextpage.presentation.screen.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextpage.BuildConfig
import com.nextpage.R
import com.nextpage.domain.update.UpdateErrorKind
import com.nextpage.presentation.theme.NextPageTheme
import com.nextpage.presentation.viewmodel.UpdateUiState
import com.nextpage.ui.components.molecules.NextPageSettingsSubPage
import com.nextpage.ui.icons.NextPageIcons

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onTerms: (() -> Unit)? = null,
    onLicenses: (() -> Unit)? = null,
    onContact: (() -> Unit)? = null,
    onRate: (() -> Unit)? = null,
    isUpdateCheckEnabled: Boolean = false,
    updateState: UpdateUiState = UpdateUiState.Idle,
    onCheckUpdates: () -> Unit = {},
) {
    NextPageSettingsSubPage(
        title = stringResource(R.string.settings_about_title),
        onBack = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cita Lincoln card centrada 280dp, padding 24dp, corner 20dp, bg surfaceVariant 30%
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.width(280.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.about_lincoln_quote),
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 25.6.sp,
                                ),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.about_lincoln_author),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Version info still visible below quote
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_build, BuildConfig.GIT_SHA),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // SDD app-auto-update: manual check row below the version block.
            // Rendered only when a feed URL is configured; empty URL keeps
            // version-display-only behavior.
            if (isUpdateCheckEnabled) {
                UpdateCheckRow(state = updateState, onCheckUpdates = onCheckUpdates)
            }

            // Lista 4 filas height 56dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutRow(labelRes = R.string.about_terms, icon = NextPageIcons.Info, onClick = onTerms)
                AboutRow(labelRes = R.string.about_licenses, icon = NextPageIcons.LibraryBooks, onClick = onLicenses)
                AboutRow(labelRes = R.string.about_contact, icon = NextPageIcons.Email, onClick = onContact)
                AboutRow(labelRes = R.string.about_rate, icon = NextPageIcons.Star, onClick = onRate)
            }

            // Footer
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                }
            is UpdateUiState.Idle -> null
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCheckUpdates)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = NextPageIcons.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_check),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Medium,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state is UpdateUiState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    imageVector = NextPageIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutRow(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(24.dp).height(24.dp),
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = NextPageIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp).height(20.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenDarkPreview() {
    NextPageTheme(darkTheme = true) {
        AboutScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenLightPreview() {
    NextPageTheme(darkTheme = false) {
        AboutScreen(onBack = {})
    }
}

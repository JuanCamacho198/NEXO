package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsSubPage

/**
 * Privacy policy, rendered from static copy.
 *
 * Every statement here is traceable to code in this repository; the source of
 * each claim is named in a comment on the section so the copy cannot drift into
 * boilerplate. Renewing the text means re-reading those sources:
 *
 * - telemetry, PII scrubbing, retention of local crash files:
 *   `NexoApplication.kt` (`SentryAndroid.init`), `SentryPiiScrubber.kt`,
 *   `SentryPrivacyPrefs.kt`
 * - credentials at rest: `EncryptedSessionSettings.kt`, `DriveTokenStore.kt`
 * - local library: `AccountStorageLayout.kt`, `AppDatabase`
 * - what leaves the device: `SupabaseBookCatalogSync.kt` (`public.user_books`,
 *   `storage.book-covers`) and the Google Drive `drive.file` scope in
 *   `GoogleDriveConfig.DRIVE_FILE_SCOPE`
 *
 * No WebView: the text ships in the APK, so it opens offline and cannot be
 * swapped by a remote page.
 */
@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.privacy_policy_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_policy_updated),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_distribution_title),
                body = stringResource(R.string.privacy_policy_distribution_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_account_title),
                body = stringResource(R.string.privacy_policy_account_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_local_title),
                body = stringResource(R.string.privacy_policy_local_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_telemetry_title),
                body = stringResource(R.string.privacy_policy_telemetry_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_telemetry_never_title),
                body = stringResource(R.string.privacy_policy_telemetry_never_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_optout_title),
                body = stringResource(R.string.privacy_policy_optout_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_drive_title),
                body = stringResource(R.string.privacy_policy_drive_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_catalogs_title),
                body = stringResource(R.string.privacy_policy_catalogs_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_permissions_title),
                body = stringResource(R.string.privacy_policy_permissions_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_retention_title),
                body = stringResource(R.string.privacy_policy_retention_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_rights_title),
                body = stringResource(R.string.privacy_policy_rights_body),
            )
            LegalSection(
                title = stringResource(R.string.privacy_policy_changes_title),
                body = stringResource(R.string.privacy_policy_changes_body),
            )
        }
    }
}

/** One titled section of a legal page. */
@Composable
private fun LegalSection(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

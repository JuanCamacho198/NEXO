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
 * Terms of use, rendered from static copy.
 *
 * The clauses describe this app's actual surface, read from the code rather
 * than from a template:
 *
 * - distribution and self-update: GitHub Releases APK, installed by the user
 *   (the `REQUEST_INSTALL_PACKAGES` path in the manifest)
 * - what the app is not: a bookstore; catalog sources live in
 *   `BuiltInSources.kt` and the public-domain download rule in
 *   `LegalPolicyScreen.kt`'s copy
 * - addons: `AddonRegistry` consent gate
 * - third-party services: Google Drive `drive.file` scope, Supabase, the
 *   public catalogs
 *
 * Like the privacy policy, it ships inside the APK and needs no network.
 */
@Composable
fun TermsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.terms_title),
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
                text = stringResource(R.string.terms_updated),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TermsSection(
                title = stringResource(R.string.terms_distribution_title),
                body = stringResource(R.string.terms_distribution_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_license_title),
                body = stringResource(R.string.terms_license_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_service_title),
                body = stringResource(R.string.terms_service_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_content_title),
                body = stringResource(R.string.terms_content_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_addons_title),
                body = stringResource(R.string.terms_addons_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_account_title),
                body = stringResource(R.string.terms_account_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_data_title),
                body = stringResource(R.string.terms_data_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_availability_title),
                body = stringResource(R.string.terms_availability_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_liability_title),
                body = stringResource(R.string.terms_liability_body),
            )
            TermsSection(
                title = stringResource(R.string.terms_changes_title),
                body = stringResource(R.string.terms_changes_body),
            )
        }
    }
}

/** One titled section of the terms. */
@Composable
private fun TermsSection(
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

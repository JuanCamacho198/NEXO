package com.nexo.presentation.feature.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.icons.NexoIcons

/**
 * Legal policy page (U5): what NEXO links to, the public-domain
 * download rule, and the addon disclosure model.
 *
 * Purely informational — no actions besides back navigation. All copy
 * lives in `values/strings.xml` + `values-es/strings.xml` (EN + voseo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legal_policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.discover_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.legal_policy_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textPrimary,
            )
            LegalPolicySection(
                title = stringResource(R.string.legal_policy_sources_title),
                body = stringResource(R.string.legal_policy_sources_body),
            )
            LegalPolicySection(
                title = stringResource(R.string.legal_policy_public_domain_title),
                body = stringResource(R.string.legal_policy_public_domain_body),
            )
            LegalPolicySection(
                title = stringResource(R.string.legal_policy_addons_title),
                body = stringResource(R.string.legal_policy_addons_body),
            )
            Text(
                text = stringResource(R.string.legal_policy_updated_note),
                style = MaterialTheme.typography.bodySmall,
                color = NexoColors.textSecondary,
            )
        }
    }
}

/** One titled section of the legal page. */
@Composable
private fun LegalPolicySection(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
        )
    }
}

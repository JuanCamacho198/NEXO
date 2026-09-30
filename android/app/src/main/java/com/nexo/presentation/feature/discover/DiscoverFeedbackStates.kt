package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.icons.NexoIcons

/** EMPTY state: 120dp illustration, query echo and three suggestion chips. */
@Composable
fun DiscoverEmptyState(
    query: String,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * U5: when true the search matched books but the active universal-source
     * filter narrowed them to zero — "sin coincidencias legales" in
     * [sourceName] instead of the generic "sin resultados" copy.
     */
    legalEmpty: Boolean = false,
    sourceName: String? = null,
) {
    if (legalEmpty && sourceName != null) {
        DiscoverLegalEmptyState(
            query = query,
            sourceName = sourceName,
            onSuggestionClick = onSuggestionClick,
            modifier = modifier,
        )
        return
    }
    val suggestions =
        listOf(
            stringResource(R.string.discover_suggestion_1),
            stringResource(R.string.discover_suggestion_2),
            stringResource(R.string.discover_suggestion_3),
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DiscoverIllustration(icon = NexoIcons.Search, tint = NexoColors.textSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        DiscoverFeedbackTitle(stringResource(R.string.discover_empty_title))
        Spacer(modifier = Modifier.height(8.dp))
        DiscoverFeedbackBody(stringResource(R.string.discover_empty_body, query))
        Spacer(modifier = Modifier.height(20.dp))
        DiscoverChipRow(
            chips = suggestions.map { DiscoverChip(label = it) },
            onChipClick = { index -> onSuggestionClick(suggestions[index]) },
        )
    }
}

/** ERROR state: non-connectivity catalog failure with a retry action. */
@Composable
fun DiscoverErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoverFeedbackState(
        icon = NexoIcons.ErrorOutline,
        iconTint = NexoColors.errorSoft,
        title = stringResource(R.string.discover_error_title),
        body = stringResource(R.string.discover_error_body),
        onRetry = onRetry,
        modifier = modifier,
    )
}

/** OFFLINE state: pre-emptive connectivity failure with a retry action. */
@Composable
fun DiscoverOfflineState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoverFeedbackState(
        icon = Icons.Rounded.Warning,
        iconTint = NexoColors.accentYellow,
        title = stringResource(R.string.discover_offline_title),
        body = stringResource(R.string.discover_offline_body),
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun DiscoverFeedbackState(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DiscoverIllustration(icon = icon, tint = iconTint)
        Spacer(modifier = Modifier.height(16.dp))
        DiscoverFeedbackTitle(title)
        Spacer(modifier = Modifier.height(8.dp))
        DiscoverFeedbackBody(body)
        Spacer(modifier = Modifier.height(20.dp))
        NexoButton(
            onClick = onRetry,
            variant = NexoButtonVariant.FILLED,
            modifier = Modifier.width(160.dp),
        ) {
            Text(text = stringResource(R.string.discover_retry))
        }
    }
}

@Composable
private fun DiscoverIllustration(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier =
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NexoColors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = tint,
        )
    }
}

@Composable
private fun DiscoverFeedbackTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = NexoColors.textPrimary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DiscoverFeedbackBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NexoColors.textSecondary,
        textAlign = TextAlign.Center,
    )
}

/**
 * U5 legal-empty state: the catalog matched books but the active
 * universal-source filter narrowed them to zero. Names the filtered source
 * so "sin coincidencias legales" never reads as "sin resultados".
 */
@Composable
private fun DiscoverLegalEmptyState(
    query: String,
    sourceName: String,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions =
        listOf(
            stringResource(R.string.discover_suggestion_1),
            stringResource(R.string.discover_suggestion_2),
            stringResource(R.string.discover_suggestion_3),
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DiscoverIllustration(icon = NexoIcons.Search, tint = NexoColors.textSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        DiscoverFeedbackTitle(stringResource(R.string.discover_empty_legal_title))
        Spacer(modifier = Modifier.height(8.dp))
        DiscoverFeedbackBody(stringResource(R.string.discover_empty_legal_body, sourceName, query))
        Spacer(modifier = Modifier.height(20.dp))
        DiscoverChipRow(
            chips = suggestions.map { DiscoverChip(label = it) },
            onChipClick = { index -> onSuggestionClick(suggestions[index]) },
        )
    }
}

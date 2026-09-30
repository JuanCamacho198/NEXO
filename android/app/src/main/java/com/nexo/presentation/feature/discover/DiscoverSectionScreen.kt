package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.viewmodel.DiscoverStatus
import com.nexo.ui.icons.NexoIcons

/**
 * "Ver todo" section list: the RESULTADOS layout minus the search section.
 *
 * The leading [IconButton] is the concrete back affordance (the design has no
 * frame for this screen): 48dp touch target, 24dp arrow, localized
 * `contentDescription`. It pops the back stack, so Discover keeps its query,
 * results and scroll position untouched underneath.
 */
@Composable
fun DiscoverSectionScreen(
    contentPadding: PaddingValues,
    viewModel: DiscoverSectionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = NexoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.discover_back),
                    modifier = Modifier.size(24.dp),
                    tint = NexoColors.textPrimary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.discover_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = NexoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sectionSubtitle(uiState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            when (uiState.status) {
                DiscoverStatus.IDLE, DiscoverStatus.LOADING -> DiscoverSkeletonState()
                DiscoverStatus.EMPTY ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.discover_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexoColors.textSecondary,
                        )
                    }
                DiscoverStatus.ERROR -> DiscoverErrorState(onRetry = viewModel::retry)
                DiscoverStatus.OFFLINE -> DiscoverOfflineState(onRetry = viewModel::retry)
                DiscoverStatus.LOADED, DiscoverStatus.LOADING_MORE ->
                    DiscoverResultsGrid(
                        books = uiState.books,
                        nextPage = uiState.nextPage,
                        isLoadingMore = uiState.status == DiscoverStatus.LOADING_MORE,
                        onOpen = viewModel::openDetail,
                        onLoadNext = viewModel::loadNextPage,
                    )
            }
        }

        DiscoverDetailSheet(
            detail = uiState.detail,
            detailStatus = uiState.detailStatus,
            download = uiState.download,
            onDownload = viewModel::startDownload,
            onCancelDownload = viewModel::cancelDownload,
            onDismiss = viewModel::dismissDetail,
        )
    }
}

/**
 * Section name plus the result count once a page has answered; while the first
 * page is in flight only the (already localized) section name is shown, so the
 * header never claims "0 resultados".
 */
@Composable
private fun sectionSubtitle(uiState: DiscoverSectionUiState): String =
    when {
        uiState.status == DiscoverStatus.LOADED || uiState.status == DiscoverStatus.LOADING_MORE ->
            stringResource(R.string.discover_section_subtitle, uiState.sectionTitle, uiState.totalCount)
        else -> uiState.sectionTitle
    }

package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogSources
import com.nexo.data.session.LegalDisclaimerPrefs
import com.nexo.presentation.feature.legal.LegalNoticeDialog
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.viewmodel.DiscoverRailGroup
import com.nexo.presentation.viewmodel.DiscoverSourceFilter
import com.nexo.presentation.viewmodel.DiscoverStatus
import com.nexo.presentation.viewmodel.DiscoverUiState
import com.nexo.presentation.viewmodel.DiscoverViewModel
import com.nexo.ui.icons.NexoIcons

/**
 * Discover shell: header, search field, trending chips, then exactly one body
 * state. The body is owned per state — rails own their Loading/Loaded/Hidden
 * lifecycle, so the shell never shows a screen-level spinner for them.
 *
 * U5 hosts the one-time legal disclaimer dialog here: it shows on entry
 * until [LegalDisclaimerPrefs] records acceptance (durable across
 * restarts), with a direct route to the legal policy page.
 *
 * @param userInitial Initial of the signed-in user, resolved by the nav call site
 *   (the screen no longer reaches into a service locator). Null renders a
 *   neutral avatar placeholder.
 * @param onOpenSection Opens the "Ver todo" list for a rail, forwarding the
 *   already-localized section title.
 */
@Composable
fun DiscoverScreen(
    contentPadding: PaddingValues,
    viewModel: DiscoverViewModel,
    userInitial: String? = null,
    onOpenSection: (DiscoverRailState.Loaded, String) -> Unit = { _, _ -> },
    onNavigateToLegalPolicy: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trendingChips = trendingChips()
    val context = LocalContext.current
    val disclaimerPrefs = remember { LegalDisclaimerPrefs(context) }
    var showDisclaimer by remember { mutableStateOf(!disclaimerPrefs.hasAccepted()) }
    if (showDisclaimer) {
        LegalNoticeDialog(
            onAccept = {
                disclaimerPrefs.accept()
                showDisclaimer = false
            },
            onViewPolicy = onNavigateToLegalPolicy,
            onDismiss = { showDisclaimer = false },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        DiscoverHeader(
            title = stringResource(R.string.discover_title),
            subtitle = headerSubtitle(uiState),
            userInitial = userInitial,
            avatarContentDescription = stringResource(R.string.discover_avatar_content_desc),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = NexoDimens.spacingMd, vertical = NexoDimens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
        ) {
            DiscoverSearchField(
                query = uiState.query,
                isSearching = uiState.isSearching,
                onQueryChange = viewModel::onQueryChange,
                onClear = { viewModel.onQueryChange("") },
                onSearch = viewModel::searchFirstPage,
            )

            if (uiState.status == DiscoverStatus.IDLE) {
                DiscoverChipRow(
                    chips = trendingChips,
                    onChipClick = { index ->
                        val term = trendingChips[index].label
                        viewModel.onQueryChange(term)
                        viewModel.searchFirstPage()
                    },
                )
            }

            // Source narrowing applies to the merged result list, so the chips are
            // only meaningful once a search has produced results — plus IDLE, where
            // the same selection narrows the featured rails instead.
            if (
                uiState.sources.isNotEmpty() &&
                (
                    uiState.status == DiscoverStatus.LOADED ||
                        uiState.status == DiscoverStatus.LOADING_MORE ||
                        uiState.status == DiscoverStatus.EMPTY ||
                        uiState.status == DiscoverStatus.IDLE
                )
            ) {
                DiscoverSourceFilterRow(
                    sources = uiState.sources,
                    selected = uiState.sourceFilter,
                    onSelect = viewModel::setSourceFilter,
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                when (uiState.status) {
                    DiscoverStatus.IDLE ->
                        DiscoverIdleRails(
                            rails = uiState.visibleRails,
                            attributionNames = uiState.attributionNames,
                            onOpenBook = viewModel::openDetail,
                            onSeeAll = onOpenSection,
                            sourceName = idleSourceName(uiState),
                            notices = uiState.visibleRailGroupNotices,
                            onRetryGroup = viewModel::retryRailGroup,
                            onDismissGroup = viewModel::dismissRailGroup,
                            onRetryRail = viewModel::retryRail,
                        )
                    DiscoverStatus.LOADING -> DiscoverSkeletonState()
                    DiscoverStatus.EMPTY ->
                        DiscoverEmptyState(
                            query = uiState.query,
                            onSuggestionClick = { term ->
                                viewModel.onQueryChange(term)
                                viewModel.searchFirstPage()
                            },
                            legalEmpty = isLegalEmpty(uiState),
                            sourceName = legalEmptySourceName(uiState),
                        )
                    DiscoverStatus.ERROR -> DiscoverErrorState(onRetry = viewModel::retry)
                    DiscoverStatus.OFFLINE -> DiscoverOfflineState(onRetry = viewModel::retry)
                    DiscoverStatus.LOADED, DiscoverStatus.LOADING_MORE ->
                        DiscoverResultsGrid(
                            books = uiState.visibleBooks,
                            nextPage = uiState.nextPage,
                            isLoadingMore = uiState.status == DiscoverStatus.LOADING_MORE,
                            onOpen = viewModel::openDetail,
                            onLoadNext = viewModel::loadNextPage,
                            attributionNames = uiState.attributionNames,
                        )
                }
            }
        }
    }

    DiscoverDetailSheet(
        detail = uiState.detail,
        detailStatus = uiState.detailStatus,
        download = uiState.download,
        onDownload = viewModel::startDownload,
        onCancelDownload = viewModel::cancelDownload,
        onDismiss = viewModel::dismissDetail,
        accessState = uiState.accessState,
        addonRead = uiState.addonRead,
        addonReadName = uiState.addonReadName,
        addonName = detailAddonName(uiState),
        onOpenAddonRead = { addonId ->
            viewModel.openAddonRead(
                addonId,
                uiState.attributionNames[addonId] ?: addonId,
            )
        },
        onAllowAccessConsent = {
            (uiState.accessState as? AccessResolverState.ConsentRequired)
                ?.addonId
                ?.let(viewModel::grantAccessConsent)
        },
        onDenyAccessConsent = viewModel::denyAccessConsent,
        onAllowAddonConsent = viewModel::grantAddonReadConsent,
        onDenyAddonConsent = viewModel::denyAccessConsent,
        onDismissAddonRead = viewModel::dismissAddonRead,
        onRetryAddonRead = viewModel::retryAddonRead,
        onRetryAccess = {
            uiState.detail?.id?.let(viewModel::openDetail)
        },
    )
}

/**
 * IDLE body: vertically scrollable stack of featured rails. A Hidden rail
 * composes nothing, so a partially servable IDLE shows only the rails that
 * actually resolved.
 *
 * The grouped failure notices come LAST, after every shelf: the shelves that
 * worked must stay on the first screen, so the reader scrolls to the notice
 * instead of being told about failure before seeing anything.
 *
 * @param sourceName Non-null when a source filter hid every rail: the column
 *   then says so instead of rendering only the addons entry.
 */
@Composable
private fun DiscoverIdleRails(
    rails: List<DiscoverRailState>,
    onOpenBook: (String) -> Unit,
    onSeeAll: (DiscoverRailState.Loaded, String) -> Unit,
    attributionNames: Map<String, String> = emptyMap(),
    sourceName: String? = null,
    notices: List<DiscoverRailGroup> = emptyList(),
    onRetryGroup: (DiscoverRailGroup) -> Unit = {},
    onDismissGroup: (DiscoverRailGroup) -> Unit = {},
    onRetryRail: (Int) -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        rails.forEachIndexed { index, rail ->
            DiscoverRailSection(
                state = rail,
                onBookClick = onOpenBook,
                onSeeAll = onSeeAll,
                attributionNames = attributionNames,
                onRetry = { onRetryRail(index) },
            )
        }
        if (sourceName != null && rails.all { it is DiscoverRailState.Hidden }) {
            Text(
                text = stringResource(R.string.discover_idle_source_empty, sourceName),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
            )
        }
        notices.forEach { group ->
            DiscoverRailGroupNotice(
                group = group,
                onRetry = { onRetryGroup(group) },
                onDismiss = { onDismissGroup(group) },
            )
        }
    }
}

/**
 * ONE grouped failure notice: every rail of [group] failed, so the reader is
 * told which source is down instead of watching the page silently get shorter.
 *
 * The copy is real [Text] inside a `role = "status"` live region, so TalkBack
 * announces the failure without the reader having to hunt for a coloured box.
 */
@Composable
private fun DiscoverRailGroupNotice(
    group: DiscoverRailGroup,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = stringResource(group.labelRes)
    val body = stringResource(R.string.discover_rail_group_failed, source)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = NexoColors.textSecondary,
        )
        Row {
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.discover_retry),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                    color = NexoColors.textAccent,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.discover_dismiss),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                    color = NexoColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun headerSubtitle(uiState: DiscoverUiState): String =
    when {
        uiState.status == DiscoverStatus.LOADED || uiState.status == DiscoverStatus.LOADING_MORE ->
            if (uiState.totalCount > 0) {
                stringResource(R.string.discover_results_count, uiState.totalCount)
            } else {
                stringResource(R.string.discover_subtitle)
            }
        else -> stringResource(R.string.discover_subtitle)
    }

@Composable
private fun trendingChips(): List<DiscoverChip> =
    listOf(
        DiscoverChip(label = stringResource(R.string.discover_trending_all), icon = NexoIcons.Sparkle),
        DiscoverChip(label = stringResource(R.string.discover_trending_popular), icon = NexoIcons.Flame),
        DiscoverChip(label = stringResource(R.string.discover_trending_scifi), icon = NexoIcons.Sparkle),
        DiscoverChip(label = stringResource(R.string.discover_trending_classics), icon = NexoIcons.Book),
    )

/**
 * U5: true when a universal-source chip narrowed a non-empty result list to
 * zero visible books — "sin coincidencias legales" in the filtered source,
 * not a globally empty catalog.
 */
private fun isLegalEmpty(uiState: DiscoverUiState): Boolean =
    uiState.books.isNotEmpty() &&
        uiState.visibleBooks.isEmpty() &&
        uiState.sourceFilter is DiscoverSourceFilter.Source

/** Display name of the source filter causing [isLegalEmpty], if known. */
@Composable
private fun legalEmptySourceName(uiState: DiscoverUiState): String? {
    val filter = uiState.sourceFilter as? DiscoverSourceFilter.Source ?: return null
    return uiState.sources.find { it.sourceId == filter.sourceId }?.name
}

/**
 * Display name of the active source filter in IDLE, so the rail column can say
 * why it is empty when a source narrows every rail to zero. Null under "All".
 */
@Composable
private fun idleSourceName(uiState: DiscoverUiState): String? = legalEmptySourceName(uiState)

/** Display name of the addon that sourced the open detail book, if any. */
private fun detailAddonName(uiState: DiscoverUiState): String? {
    val addonId = uiState.detail?.provider?.let(CatalogSources::addonIdOf) ?: return null
    return uiState.attributionNames[addonId]
}

package com.nexo.presentation.feature.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoSkeletonBox

/**
 * Fail-closed state of one IDLE featured rail.
 *
 * A rail that cannot be served (no capability, upstream error, empty page) is
 * [Hidden] and its section is not composed at all — never an empty rail, never a
 * placeholder header. There is deliberately no screen-level rail status: each
 * rail resolves on its own.
 */
sealed interface DiscoverRailState {
    data object Hidden : DiscoverRailState

    data object Loading : DiscoverRailState

    data class Loaded(
        @param:StringRes val sectionTitleRes: Int,
        val sort: CatalogFeaturedSort?,
        val sourceId: String?,
        val books: List<CatalogBook>,
        val totalCount: Int,
        /** Addon display name for a per-addon rail; null for featured rails. */
        val addonName: String? = null,
    ) : DiscoverRailState
}

private val RAIL_CARD_WIDTH = 110.dp
private val RAIL_COVER_HEIGHT = 165.dp

/**
 * IDLE rail: a section header (fs 18 title + accent "Ver todo" text button) over
 * a horizontal [LazyRow] of [RAIL_CARD_WIDTH] cards. [DiscoverRailState.Hidden]
 * renders nothing.
 *
 * @param onBookClick Opens the tapped book's detail sheet.
 * @param onSeeAll Opens the full section list for a loaded rail, carrying the
 *   already-localized section title so the route needs no resource-id round trip.
 */
@Composable
fun DiscoverRailSection(
    state: DiscoverRailState,
    onBookClick: (String) -> Unit,
    onSeeAll: (DiscoverRailState.Loaded, String) -> Unit,
    modifier: Modifier = Modifier,
    attributionNames: Map<String, String> = emptyMap(),
) {
    when (state) {
        DiscoverRailState.Hidden -> Unit
        DiscoverRailState.Loading ->
            Column(modifier = modifier.fillMaxWidth()) {
                NexoSkeletonBox(
                    modifier =
                        Modifier
                            .width(180.dp)
                            .height(20.dp),
                    radius = 4.dp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(RAIL_PLACEHOLDER_COUNT) {
                        NexoSkeletonBox(
                            modifier =
                                Modifier
                                    .width(RAIL_CARD_WIDTH)
                                    .height(RAIL_COVER_HEIGHT),
                            radius = 8.dp,
                        )
                    }
                }
            }
        is DiscoverRailState.Loaded -> {
            val sectionTitle =
                if (state.sourceId != null && state.addonName != null) {
                    stringResource(R.string.discover_rail_from, state.addonName)
                } else {
                    stringResource(state.sectionTitleRes)
                }
            Column(modifier = modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = NexoColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSeeAll(state, sectionTitle) }) {
                        Text(
                            text = stringResource(R.string.discover_view_all),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            color = NexoColors.textAccent,
                        )
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.books, key = { it.id }) { book ->
                        DiscoverRailCard(
                            book = book,
                            onOpen = onBookClick,
                            attributionNames = attributionNames,
                        )
                    }
                }
            }
        }
    }
}

/** One horizontal rail cell: 110x165 cover with the shared letter-initial fallback, title below. */
@Composable
private fun DiscoverRailCard(
    book: CatalogBook,
    onOpen: (String) -> Unit,
    attributionNames: Map<String, String> = emptyMap(),
) {
    Column(
        modifier =
            Modifier
                .width(RAIL_CARD_WIDTH)
                .clickable { onOpen(book.id) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiscoverBookCover(
            coverUrl = book.coverUrl,
            title = book.title,
            surface = "rail",
            modifier =
                Modifier
                    .width(RAIL_CARD_WIDTH)
                    .height(RAIL_COVER_HEIGHT),
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SourceAttributionBadge(
            provider = book.provider,
            attributionNames = attributionNames,
        )
        AccessBadge(book = book)
    }
}

/**
 * "Manage addons" affordance appended to the IDLE rail column. Emits the
 * settings/addons route; the nav call site owns the actual navigation so this
 * section stays free of navigation logic.
 */
@Composable
fun DiscoverManageAddonsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.discover_manage_addons),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
            color = NexoColors.textAccent,
        )
    }
}

private const val RAIL_PLACEHOLDER_COUNT = 3

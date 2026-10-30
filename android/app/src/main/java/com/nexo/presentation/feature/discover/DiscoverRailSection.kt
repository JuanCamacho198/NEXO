package com.nexo.presentation.feature.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.NexoSkeletonBox
import com.nexo.ui.components.molecules.NexoCardSurface

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

    /**
     * An upstream failure kept in place instead of hidden: the section renders
     * its own inline error and retry, and a source whose every rail failed adds
     * ONE grouped notice below the shelves (never a second copy per rail).
     *
     * [sourceId] identifies the owning source, so a source filter can still
     * attribute the failure to its shelf.
     */
    data class Error(
        val code: CatalogErrorCode,
        val sourceId: String,
    ) : DiscoverRailState

    data class Loaded(
        @param:StringRes val sectionTitleRes: Int,
        val sort: CatalogFeaturedSort?,
        val sourceId: String?,
        val books: List<CatalogBook>,
        val totalCount: Int,
        /** Addon display name for a per-addon rail; null for featured rails. */
        val addonName: String? = null,
        /**
         * Gutendex topic term for a topic shelf; null for featured and addon
         * rails. Carried so "See all" can page the same `topic` query the shelf
         * used.
         */
        val topicTerm: String? = null,
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
    onRetry: () -> Unit = {},
) {
    when (state) {
        DiscoverRailState.Hidden -> Unit
        is DiscoverRailState.Error -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(railErrorCopy(state.code)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                )
                TextButton(onClick = onRetry) {
                    Text(
                        text = stringResource(R.string.discover_retry),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                        color = NexoColors.textAccent,
                    )
                }
            }
        }
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

/**
 * One horizontal rail cell: a 110x165 slot plus title. When the book has no
 * usable cover the slot is a TEXT CARD — the title and author set on the theme's
 * surface — instead of an empty frame, so a coverless shelf still reads as
 * deliberate typography rather than a broken image.
 */
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
        if (book.coverUrl.isNullOrBlank()) {
            RailTextCard(
                title = book.title,
                author = book.authors.joinToString(", "),
                modifier =
                    Modifier
                        .width(RAIL_CARD_WIDTH)
                        .height(RAIL_COVER_HEIGHT),
            )
        } else {
            DiscoverBookCover(
                coverUrl = book.coverUrl,
                title = book.title,
                surface = "rail",
                modifier =
                    Modifier
                        .width(RAIL_CARD_WIDTH)
                        .height(RAIL_COVER_HEIGHT),
            )
        }
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
 * Coverless rail slot: title and author on the shared card surface with a short
 * accent rule, so it reads as a typographic card. Every colour comes from
 * [NexoColors]; no literal palette value is used here.
 */
@Composable
private fun RailTextCard(
    title: String,
    author: String,
    modifier: Modifier = Modifier,
) {
    NexoCardSurface(
        modifier = modifier.testTag(RAIL_TEXT_CARD_TAG),
        contentPadding = PaddingValues(NexoDimens.spacingSm),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingXs)) {
                if (author.isNotBlank()) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = NexoColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(NexoColors.primary.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/** Test tag for the coverless rail slot, so UI tests can assert the text card. */
const val RAIL_TEXT_CARD_TAG = "discover_rail_text_card"

private const val RAIL_PLACEHOLDER_COUNT = 3

/**
 * Inline rail failure copy, split the same way desktop splits it (offline /
 * slow / rate-limited / upstream) so a timeout or a 429 never reads as "no
 * connection". The codes match `CatalogErrorCode`; anything else is upstream.
 */
@StringRes
internal fun railErrorCopy(code: CatalogErrorCode): Int =
    when (code) {
        CatalogErrorCode.NETWORK_ERROR -> R.string.discover_offline
        CatalogErrorCode.RATE_LIMITED -> R.string.discover_error_rate_limited
        else -> R.string.discover_error_upstream
    }

package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.presentation.theme.NexoColors

/** Rows from the end of the list that trigger the next-page prefetch. */
private const val PREFETCH_THRESHOLD = 3

/**
 * RESULTADOS grid: a fixed 2-column layout of [DiscoverCard] cells.
 *
 * The near-end prefetch only ever fires while a next page exists, and the footer
 * shows [R.string.discover_end_of_results] once `nextPage == null` — after that
 * point no further fetch can be scheduled from the grid.
 *
 * @param books Accumulated results (page 1..n, de-duplicated upstream).
 * @param nextPage Next 1-based page, or null when the list is exhausted.
 * @param isLoadingMore When true the footer renders skeleton bars instead of an
 *   end marker.
 * @param onLoadNext Requests the next page; ignored by this composable when
 *   [nextPage] is null.
 */
@Composable
fun DiscoverResultsGrid(
    books: List<CatalogBook>,
    nextPage: Int?,
    isLoadingMore: Boolean,
    onOpen: (String) -> Unit,
    onLoadNext: () -> Unit,
    modifier: Modifier = Modifier,
    attributionNames: Map<String, String> = emptyMap(),
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
            DiscoverCard(book = book, onOpen = onOpen, attributionNames = attributionNames)
            if (nextPage != null && index >= books.lastIndex - PREFETCH_THRESHOLD) {
                LaunchedEffect(books.size) { onLoadNext() }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                isLoadingMore -> DiscoverLoadingMoreFooter(modifier = Modifier.padding(8.dp))
                nextPage == null ->
                    Text(
                        text = stringResource(R.string.discover_end_of_results),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexoColors.textSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
            }
        }
    }
}

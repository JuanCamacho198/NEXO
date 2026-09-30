package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoSkeletonBox

/** Number of placeholder cards in the initial LOADING skeleton (2x3 grid). */
private const val SKELETON_CARD_COUNT = 6

/**
 * LOADING content: a 2x3 grid of skeleton cards plus the "Buscando…" spinner
 * row. Replaces stale results while the first page loads.
 */
@Composable
fun DiscoverSkeletonState(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SKELETON_CARD_COUNT) {
            DiscoverSkeletonCard()
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = NexoColors.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.discover_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DiscoverSkeletonCard() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NexoSkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(109f / 168f),
            radius = 8.dp,
        )
        NexoSkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth(fraction = 0.9f)
                    .height(10.dp),
            radius = 4.dp,
        )
        NexoSkeletonBox(
            modifier =
                Modifier
                    .width(64.dp)
                    .height(8.dp),
            radius = 4.dp,
        )
    }
}

/**
 * LOADING_MORE footer: two skeleton bars appended below the existing results
 * so the grid keeps its content while the next page loads.
 */
@Composable
fun DiscoverLoadingMoreFooter(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NexoSkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            radius = 8.dp,
        )
        NexoSkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            radius = 8.dp,
        )
    }
}

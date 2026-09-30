package com.nexo.presentation.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.presentation.viewmodel.DiscoverSourceFilter

/**
 * Horizontal source-filter chips built from [sources] (provider order), with a
 * leading "All" chip as the default selection. Selecting a chip narrows the
 * in-memory merged result list by `CatalogBook.provider`; the selected chip is
 * tinted `primary` by [DiscoverChipRow].
 *
 * @param selected Active filter; [DiscoverSourceFilter.AllSources] selects the
 *   leading chip.
 * @param onSelect Emitted with the tapped chip's filter.
 */
@Composable
fun DiscoverSourceFilterRow(
    sources: List<CatalogSourceInfo>,
    selected: DiscoverSourceFilter,
    onSelect: (DiscoverSourceFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allLabel = stringResource(R.string.discover_source_filter_all)
    val chips =
        buildList {
            add(DiscoverChip(label = allLabel, selected = selected is DiscoverSourceFilter.AllSources))
            sources.forEach { source ->
                add(
                    DiscoverChip(
                        label = source.name,
                        selected =
                            selected is DiscoverSourceFilter.Source &&
                                selected.sourceId == source.sourceId,
                    ),
                )
            }
        }

    DiscoverChipRow(
        chips = chips,
        onChipClick = { index ->
            if (index == 0) {
                onSelect(DiscoverSourceFilter.AllSources)
            } else {
                onSelect(DiscoverSourceFilter.Source(sources[index - 1].sourceId))
            }
        },
        modifier = modifier,
    )
}

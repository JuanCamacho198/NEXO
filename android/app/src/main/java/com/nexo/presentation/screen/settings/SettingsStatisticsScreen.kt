package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.presentation.screen.StatisticsScreen
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.ui.components.molecules.NexoSettingsSubPage

/**
 * Stays in `:app` on purpose: it delegates to [StatisticsScreen], which reads
 * [StatisticsViewModel] and the statistics copy, and needs `:app`'s statistics
 * feature. Moving it into `:feature:settings` would make the feature depend on
 * `:app`. The two call-site strings it renders are therefore passed in by the
 * caller instead of being resolved here.
 */
@Composable
fun SettingsStatisticsScreen(
    viewModel: StatisticsViewModel,
    onBack: () -> Unit,
    title: String = stringResource(R.string.statistics_title),
) {
    NexoSettingsSubPage(
        title = title,
        onBack = onBack,
    ) {
        StatisticsScreen(
            contentPadding = PaddingValues(),
            viewModel = viewModel,
            showHeader = false,
        )
    }
}

package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.presentation.screen.StatisticsScreen
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.ui.components.molecules.NexoSettingsSubPage

@Composable
fun SettingsStatisticsScreen(
    viewModel: StatisticsViewModel,
    onBack: () -> Unit,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.statistics_title),
        onBack = onBack,
    ) {
        StatisticsScreen(
            contentPadding = PaddingValues(),
            viewModel = viewModel,
        )
    }
}

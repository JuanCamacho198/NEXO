package com.nexo.di.modules

import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import java.time.ZoneId

class UseCaseModule(
    private val repositoryModule: RepositoryModule,
    private val preferencesModule: PreferencesModule,
) {
    val updateReadingProgressUseCase: UpdateReadingProgressUseCase by lazy {
        UpdateReadingProgressUseCase(repositoryModule.readerRepository)
    }

    val getStatisticsUseCase: GetStatisticsUseCase by lazy {
        GetStatisticsUseCase(
            readingStatsRepository = repositoryModule.readingStatsRepository,
            homeRepository = repositoryModule.homeRepository,
            dailyGoalProvider = preferencesModule.dailyGoalProvider,
            zoneId = ZoneId.systemDefault(),
        )
    }

    val getBookProgressUseCase: GetBookProgressUseCase by lazy {
        GetBookProgressUseCase(
            readerRepository = repositoryModule.readerRepository,
        )
    }
}

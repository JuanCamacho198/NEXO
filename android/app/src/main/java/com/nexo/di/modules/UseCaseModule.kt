package com.nexo.di.modules

import com.nexo.data.session.UpdatePrefs
import com.nexo.data.update.UpdateDownloader
import com.nexo.data.update.UpdateFeedService
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateNetworkGate
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    @Provides
    @Singleton
    fun provideUpdateReadingProgressUseCase(
        readerRepository: ReaderRepository,
    ): UpdateReadingProgressUseCase = UpdateReadingProgressUseCase(readerRepository)

    @Provides
    @Singleton
    fun provideGetStatisticsUseCase(
        readingStatsRepository: ReadingStatsRepository,
        homeRepository: HomeRepository,
        dailyGoalProvider: () -> Int,
    ): GetStatisticsUseCase =
        GetStatisticsUseCase(
            readingStatsRepository = readingStatsRepository,
            homeRepository = homeRepository,
            dailyGoalProvider = dailyGoalProvider,
            zoneId = ZoneId.systemDefault(),
        )

    @Provides
    @Singleton
    fun provideGetBookProgressUseCase(
        readerRepository: ReaderRepository,
    ): GetBookProgressUseCase = GetBookProgressUseCase(readerRepository)

    @Provides
    @Singleton
    fun provideCheckForUpdatesUseCase(
        fetcher: UpdateFeedService,
        suppression: UpdatePrefs,
        networkGate: UpdateNetworkGate,
    ): CheckForUpdatesUseCase =
        CheckForUpdatesUseCase(
            fetcher = fetcher,
            suppression = suppression,
            networkGate = networkGate,
        )

    /**
     * Kept apart from [provideCheckForUpdatesUseCase] so the downloader is only
     * built when the update dialog actually installs something.
     */
    @Provides
    @Singleton
    fun provideUpdateDownloader(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): UpdateDownloader = UpdateDownloader(context)
}

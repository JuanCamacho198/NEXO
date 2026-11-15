package com.nexo.di.modules

import android.content.Context
import com.nexo.data.repository.CacheRepositoryImpl
import com.nexo.data.repository.DictionaryRepositoryImpl
import com.nexo.data.repository.HomeRepositoryImpl
import com.nexo.data.repository.LibraryRepositoryImpl
import com.nexo.data.repository.ReaderRepositoryImpl
import com.nexo.data.repository.RoomTranslationHistoryRepository
import com.nexo.data.repository.ReadingStatsRepositoryImpl
import com.nexo.data.repository.StorageRepositoryImpl
import com.nexo.di.AppStartTimings
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.storage.AppInternalCoverStorage
import com.nexo.data.sync.DictionarySyncService
import com.nexo.data.sync.SessionGateImpl
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.repository.TranslationHistoryRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SessionGate
import com.nexo.domain.sync.SyncSettleGate
import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.remote.sync.SyncOrchestratorSettleGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideSessionGate(
        sessionManager: com.nexo.data.session.SessionManager,
    ): SessionGate = SessionGateImpl(sessionManager)

    /**
     * Settle signal consumed by local file cleanup: a delete waits (bounded)
     * for the aggregate sync state to leave `SyncState.Active`.
     *
     * Depends on the orchestrator directly. The previous `() -> SyncSettleGate`
     * lambda existed only because the manual container built its repository
     * module before the orchestrator; Hilt resolves the chain without it.
     */
    @Provides
    @Singleton
    fun provideSyncSettleGate(
        orchestrator: SyncOrchestrator,
    ): SyncSettleGate = SyncOrchestratorSettleGate(orchestrator)

    @Provides
    @Singleton
    fun provideLibraryRepository(
        @ApplicationContext context: Context,
        bookDao: BookDao,
        readingStatsDao: ReadingStatsDao,
        epubParserService: com.nexo.data.epub.EpubParserService,
        pdfParserService: com.nexo.data.pdf.PdfParserService,
        coverStorage: AppInternalCoverStorage,
        readingProgressDao: ReadingProgressDao,
        outboxDao: SyncOutboxDao,
        settleGate: SyncSettleGate,
        drainScheduler: OutboxDrainScheduler,
        timings: AppStartTimings,
    ): LibraryRepository {
        val start = System.currentTimeMillis()
        val repository =
            LibraryRepositoryImpl(
                appContext = context,
                bookDao = bookDao,
                readingStatsDao = readingStatsDao,
                epubParserService = epubParserService,
                pdfParserService = pdfParserService,
                coverStorage = coverStorage,
                readingProgressDao = readingProgressDao,
                outboxDao = outboxDao,
                settleGate = settleGate,
                drainScheduler = drainScheduler,
            )
        timings.recordEpubImportInit(System.currentTimeMillis() - start)
        return repository
    }

    @Provides
    @Singleton
    fun provideReaderRepository(
        readingProgressDao: ReadingProgressDao,
        highlightDao: HighlightDao,
        bookmarkDao: BookmarkDao,
        bookDao: BookDao,
        outboxDao: SyncOutboxDao,
        drainScheduler: OutboxDrainScheduler,
        timings: AppStartTimings,
    ): ReaderRepository {
        val start = System.currentTimeMillis()
        val repository =
            ReaderRepositoryImpl(
                readingProgressDao = readingProgressDao,
                highlightDao = highlightDao,
                bookmarkDao = bookmarkDao,
                bookDao = bookDao,
                outboxDao = outboxDao,
                drainScheduler = drainScheduler,
            )
        timings.recordReaderRepoInit(System.currentTimeMillis() - start)
        return repository
    }

    @Provides
    @Singleton
    fun provideReadingStatsRepository(
        readingStatsDao: ReadingStatsDao,
        readingSessionDao: ReadingSessionDao,
        outboxDao: SyncOutboxDao,
        drainScheduler: OutboxDrainScheduler,
    ): ReadingStatsRepository =
        ReadingStatsRepositoryImpl(
            readingStatsDao = readingStatsDao,
            readingSessionDao = readingSessionDao,
            outboxDao = outboxDao,
            drainScheduler = drainScheduler,
            zoneId = ZoneId.systemDefault(),
        )

    @Provides
    @Singleton
    fun provideHomeRepository(
        bookDao: BookDao,
        readingProgressDao: ReadingProgressDao,
        readingSessionDao: ReadingSessionDao,
    ): HomeRepository =
        HomeRepositoryImpl(
            bookDao = bookDao,
            readingProgressDao = readingProgressDao,
            readingSessionDao = readingSessionDao,
            zoneId = ZoneId.systemDefault(),
        )

    @Provides
    @Singleton
    fun provideDictionaryRepository(
        dao: DictionaryWordDao,
        sync: DictionarySyncService,
    ): DictionaryRepository = DictionaryRepositoryImpl(dao = dao, sync = sync)

    @Provides
    @Singleton
    fun provideCacheRepository(
        @ApplicationContext context: Context,
        discoverCacheDao: DiscoverCacheDao,
        imageLoader: coil3.ImageLoader,
    ): CacheRepository =
        CacheRepositoryImpl(
            discoverCacheDao = discoverCacheDao,
            appContext = context,
            imageLoader = imageLoader,
        )

    @Provides
    @Singleton
    fun provideStorageRepository(
        @ApplicationContext context: Context,
        bookDao: BookDao,
        settleGate: SyncSettleGate,
    ): StorageRepository =
        StorageRepositoryImpl(
            appContext = context,
            bookDao = bookDao,
            settleGate = settleGate,
        )

    @Provides
    @Singleton
    fun provideTranslationHistoryRepository(
        cacheDao: TranslationCacheDao,
        activeUserProvider: ActiveUserProvider,
    ): TranslationHistoryRepository =
        RoomTranslationHistoryRepository(
            cacheDao = cacheDao,
            activeUser = activeUserProvider,
        )
}

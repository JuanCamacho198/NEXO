package com.nexo.di

import android.content.Context
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.RetentionPruner
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.remote.sync.SyncOrchestratorImpl
import com.nexo.data.remote.sync.SyncOrchestratorSettleGate
import com.nexo.data.remote.sync.SyncService
import com.nexo.data.remote.work.WorkManagerRetentionPruneScheduler
import com.nexo.data.sync.DictionarySyncService
import com.nexo.domain.repository.AuthRepository
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SessionGate
import com.nexo.domain.sync.SyncSettleGate
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.presentation.debug.InitTimingsSection
import com.nexo.presentation.navigation.InstallDeepLinkController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

/** WS2c slice 6: one Hilt-owned [AppContainer] serves VMs and entry points. */
@Module
@InstallIn(SingletonComponent::class)
object HiltSingletonsModule {
    @Provides @Singleton
    fun provideAppContainer(
        @ApplicationContext c: Context,
    ): AppContainer = AppContainer(c)

    @Provides @Singleton
    fun provideLibraryRepository(a: AppContainer): LibraryRepository = a.libraryRepository

    @Provides @Singleton
    fun provideImportEpub(r: LibraryRepository): ImportEpubBookUseCase = ImportEpubBookUseCase(r)

    @Provides @Singleton
    fun provideSyncService(a: AppContainer): SyncService = a.syncService

    @Provides @Singleton
    fun provideCatalogSync(a: AppContainer): SupabaseBookCatalogSync = a.supabaseBookCatalogSync

    @Provides @Singleton
    fun provideReaderRepository(a: AppContainer): ReaderRepository = a.readerRepository

    @Provides @Singleton
    fun provideBookProgress(a: AppContainer): GetBookProgressUseCase = a.getBookProgressUseCase

    @Provides @Singleton
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides @Singleton
    fun provideHomeRepository(a: AppContainer): HomeRepository = a.homeRepository

    @Provides @Singleton
    fun provideProgressSync(a: AppContainer): SupabaseProgressSync = a.supabaseProgressSync

    @Provides @Singleton
    fun provideStatistics(a: AppContainer): GetStatisticsUseCase = a.getStatisticsUseCase

    @Provides @Singleton
    fun provideAuthRepository(a: AppContainer): AuthRepository = a.authRepository

    @Provides @Singleton
    fun provideSyncOrchestrator(a: AppContainer): SyncOrchestrator = a.syncOrchestrator

    /** S6: WorkManager-backed outbox drain scheduler (scheduler-only). */
    @Provides @Singleton
    fun provideOutboxDrainScheduler(a: AppContainer): OutboxDrainScheduler = a.outboxDrainScheduler

    /** FR-14: Room database for the retention pruner + its worker. */
    @Provides @Singleton
    fun provideAppDatabase(a: AppContainer): AppDatabase = a.appDatabase

    /** FR-14: prune + VACUUM implementation consumed by RetentionPruneWorker. */
    @Provides @Singleton
    fun provideRetentionPruner(a: AppContainer): RetentionPruner = a.retentionPruner

    /** FR-14: WorkManager-backed periodic retention scheduler (scheduler-only). */
    @Provides @Singleton
    fun provideRetentionPruneScheduler(a: AppContainer): WorkManagerRetentionPruneScheduler = a.retentionPruneScheduler

    @Provides
    @Named("isAuthConfigured")
    fun provideAuthConfigured(a: AppContainer): Boolean = !a.isAuthConfigError

    @Provides @Singleton
    @Named("hasAuthWiringIssue")
    fun provideHasAuthWiringIssue(): Boolean = false

    @Provides fun provideDailyGoalProvider(a: AppContainer): @JvmSuppressWildcards () -> Int = a.dailyGoalProvider

    @Provides @Singleton
    fun provideInitTimings(a: AppContainer): InitTimingsSection =
        InitTimingsSection(
            a.dbInitTimeMs,
            a.epubImportInitTimeMs,
            a.readerRepoInitTimeMs,
            a.totalInitTimeMs,
        )

    @Provides @Singleton
    fun provideBookDao(a: AppContainer): BookDao = a.bookDao

    @Provides @Singleton
    fun provideHighlightDao(a: AppContainer): HighlightDao = a.highlightDao

    @Provides @Singleton
    fun provideBookmarkDao(a: AppContainer): BookmarkDao = a.bookmarkDao

    @Provides @Singleton
    fun provideReadingSessionDao(a: AppContainer): ReadingSessionDao = a.readingSessionDao

    @Provides @Singleton
    fun provideReadingProgressDao(a: AppContainer): ReadingProgressDao = a.readingProgressDao

    @Provides fun provideProgressSyncProvider(a: AppContainer): @JvmSuppressWildcards () -> SupabaseProgressSync =
        {
            a.supabaseProgressSync
        }

    @Provides fun provideClearAllData(a: AppContainer): @JvmSuppressWildcards () -> Unit = { a.clearAllData() }

    @Provides fun provideSyncServiceProvider(a: AppContainer): @JvmSuppressWildcards () -> SyncService = { a.syncService }
}

/**
 * Shared factory for the app-lifetime [SyncOrchestrator] singleton.
 *
 * Used by [HiltSingletonsModule.provideSyncOrchestrator] (Hilt graph) and by
 * the manual [AppContainer] graph, so both graphs construct the identical
 * instance type from identical inputs. The default [externalScope] matches
 * the historical manual wiring (SupervisorJob + Default dispatcher).
 */
fun createSyncOrchestrator(
    drive: SyncService,
    catalog: SupabaseBookCatalogSync,
    progress: SupabaseProgressSync,
    gate: SessionGate,
    outboxDao: SyncOutboxDao,
    dictionary: DictionarySyncService? = null,
    externalScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
): SyncOrchestrator =
    SyncOrchestratorImpl(
        drive = drive,
        catalog = catalog,
        progress = progress,
        gate = gate,
        outboxDao = outboxDao,
        dictionary = dictionary,
        externalScope = externalScope,
    )

/**
 * Shared factory for the app-lifetime [SyncSettleGate] singleton.
 *
 * Used by [HiltSingletonsModule.provideSyncSettleGate] (Hilt graph) and by
 * the manual [AppContainer] graph.
 */
fun createSyncSettleGate(
    orchestrator: SyncOrchestrator,
    timeoutMillis: Long = SyncOrchestratorSettleGate.DEFAULT_TIMEOUT_MILLIS,
): SyncSettleGate =
    SyncOrchestratorSettleGate(
        orchestrator = orchestrator,
        timeoutMillis = timeoutMillis,
    )

/**
 * Shared factory for the app-lifetime [InstallDeepLinkController] singleton.
 *
 * Used by [HiltSingletonsModule.provideInstallDeepLinkController] (Hilt
 * graph) and by the manual [AppContainer] graph. The default
 * [mainDispatcher] matches the historical manual wiring (Main dispatcher;
 * the controller owns a Main-scoped StateFlow read by the Compose dialog
 * host).
 */
fun createInstallDeepLinkController(
    registry: AddonRegistryLike,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
): InstallDeepLinkController =
    InstallDeepLinkController(
        registry = registry,
        mainDispatcher = mainDispatcher,
    )

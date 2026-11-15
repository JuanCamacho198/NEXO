package com.nexo.di

import android.content.Context
import com.nexo.BuildConfig
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.RetentionPruner
import com.nexo.data.local.dao.NotificationDao
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.remote.sync.SyncOrchestratorImpl
import com.nexo.data.remote.sync.SyncOrchestratorSettleGate
import com.nexo.data.remote.sync.SyncService
import com.nexo.data.remote.work.WorkManagerOutboxDrainScheduler
import com.nexo.data.remote.work.WorkManagerRetentionPruneScheduler
import com.nexo.data.repository.NotificationRepository
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.SessionManager
import com.nexo.data.sync.DictionarySyncService
import com.nexo.domain.notification.AppForegroundSignal
import com.nexo.domain.notification.NotificationStore
import com.nexo.domain.notification.NotificationSurfaces
import com.nexo.domain.notification.ToastSink
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SessionGate
import com.nexo.domain.sync.SyncSettleGate
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.domain.repository.LibraryRepository
import com.nexo.presentation.navigation.InstallDeepLinkController
import com.nexo.presentation.notification.AndroidOsNotifier
import com.nexo.presentation.notification.NotificationToastBus
import com.nexo.presentation.notification.ProcessLifecycleAppForegroundSignal
import com.nexo.presentation.notification.UiEventToastSink
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

@Module
@InstallIn(SingletonComponent::class)
object HiltSingletonsModule {
    @Provides
    @Singleton
    fun provideActiveUserProvider(): ActiveUserProvider = ActiveUserProvider()

    @Provides @Singleton fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides @Singleton
    fun provideImportEpub(r: LibraryRepository): ImportEpubBookUseCase =
        ImportEpubBookUseCase(r, SentryTelemetrySink)

    /** WS2c slice 6: the app-lifetime sync orchestrator. */
    @Provides
    @Singleton
    fun provideSyncOrchestrator(
        drive: SyncService,
        catalog: SupabaseBookCatalogSync,
        progress: SupabaseProgressSync,
        dictionary: DictionarySyncService,
        gate: SessionGate,
        outboxDao: com.nexo.data.local.dao.SyncOutboxDao,
    ): SyncOrchestrator =
        createSyncOrchestrator(
            drive = drive,
            catalog = catalog,
            progress = progress,
            dictionary = dictionary,
            gate = gate,
            outboxDao = outboxDao,
        )

    /** S6: WorkManager-backed outbox drain scheduler (scheduler-only). */
    @Provides
    @Singleton
    fun provideOutboxDrainScheduler(
        @ApplicationContext context: Context,
        sessionManager: SessionManager,
    ): OutboxDrainScheduler =
        WorkManagerOutboxDrainScheduler(
            context = context,
            sessionManager = sessionManager,
        )

    /** FR-14: prune + VACUUM implementation consumed by RetentionPruneWorker. */
    @Provides
    @Singleton
    fun provideRetentionPruner(db: AppDatabase): RetentionPruner = RetentionPruner(db)

    /** FR-14: WorkManager-backed periodic retention scheduler (scheduler-only). */
    @Provides
    @Singleton
    fun provideRetentionPruneScheduler(
        @ApplicationContext context: Context,
    ): WorkManagerRetentionPruneScheduler = WorkManagerRetentionPruneScheduler(context = context)

    @Provides
    @Singleton
    @Named("isAuthConfigured")
    fun provideAuthConfigured(): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    @Provides
    @Singleton
    @Named("hasAuthWiringIssue")
    fun provideHasAuthWiringIssue(): Boolean = false

    @Provides
    @Singleton
    fun provideDailyGoalProvider(
        readingGoalPreferences: ReadingGoalPreferences,
    ): @JvmSuppressWildcards () -> Int = { readingGoalPreferences.load() ?: 30 }

    @Provides
    @Singleton
    fun provideInitTimings(timings: AppStartTimings): com.nexo.presentation.debug.InitTimingsSection =
        timings.snapshot()

    // ── Notifications (NOTIF-02 / U7 / U9) ──────────────────────────────

    @Provides
    @Singleton
    fun provideNotificationRepository(
        dao: NotificationDao,
    ): NotificationRepository = NotificationRepository(dao)

    @Provides
    @Singleton
    fun provideNotificationPreferencesStore(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): NotificationPreferencesStore = NotificationPreferencesStore(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideNotificationToastBus(): NotificationToastBus = NotificationToastBus()

    @Provides
    @Singleton
    fun provideNotificationToastSink(
        @ApplicationContext context: Context,
        bus: NotificationToastBus,
    ): ToastSink =
        UiEventToastSink(
            emit = { event -> bus.publish(event) },
            resolveMessage = { request ->
                UiEventToastSink.format(
                    context = context,
                    i18nKey = request.i18nKey,
                    params = request.i18nParams,
                )
            },
        )

    /**
     * Process-wide foreground signal. The observer attaches on first access, so
     * constructing the graph never touches ProcessLifecycleOwner.
     */
    @Provides
    @Singleton
    fun provideAppForegroundSignal(): AppForegroundSignal = ProcessLifecycleAppForegroundSignal.install()

    @Provides
    @Singleton
    fun provideNotificationStore(
        @ApplicationContext context: Context,
        history: NotificationRepository,
        toastSink: ToastSink,
        foreground: AppForegroundSignal,
        preferences: NotificationPreferencesStore,
    ): NotificationStore =
        NotificationStore(
            history = history,
            surfaces = NotificationSurfaces(toastSink = toastSink, osNotifier = AndroidOsNotifier(context)),
            foreground = foreground,
            preferencesProvider = { preferences.load() },
        )

    // ── Lambda seams ────────────────────────────────────────────────────

    @Provides
    fun provideProgressSyncProvider(
        sync: SupabaseProgressSync,
    ): @JvmSuppressWildcards () -> SupabaseProgressSync = { sync }

    @Provides
    fun provideClearAllData(
        db: AppDatabase,
    ): @JvmSuppressWildcards () -> Unit = { db.clearAllTables() }

    @Provides
    fun provideSyncServiceProvider(
        sync: SyncService,
    ): @JvmSuppressWildcards () -> SyncService = { sync }
}

/**
 * Shared factory for the app-lifetime [SyncOrchestrator] singleton.
 *
 * The default [externalScope] matches the historical manual wiring
 * (SupervisorJob + Default dispatcher).
 */
fun createSyncOrchestrator(
    drive: SyncService,
    catalog: SupabaseBookCatalogSync,
    progress: SupabaseProgressSync,
    gate: SessionGate,
    outboxDao: com.nexo.data.local.dao.SyncOutboxDao,
    dictionary: DictionarySyncService? = null,
    externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
 * The default [mainDispatcher] matches the historical manual wiring.
 */
fun createInstallDeepLinkController(
    registry: AddonRegistryLike,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
): InstallDeepLinkController =
    InstallDeepLinkController(
        registry = registry,
        mainDispatcher = mainDispatcher,
    )

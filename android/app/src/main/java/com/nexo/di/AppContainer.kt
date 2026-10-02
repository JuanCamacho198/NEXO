package com.nexo.di

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import com.nexo.BuildConfig
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.remote.catalog.CatalogFileDownloader
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.drive.DriveCoordinator
import com.nexo.data.remote.drive.DriveOAuthSession
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.remote.supabase.SupabaseBookCatalogDataSource
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressDataSource
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.DriveColdBackupService
import com.nexo.data.remote.sync.StorageSyncRemoteDataSource
import com.nexo.data.remote.sync.SyncOrchestrator
import com.nexo.data.remote.sync.SyncService
import com.nexo.data.remote.work.WorkManagerOutboxDrainScheduler
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.SessionManager
import com.nexo.data.session.UpdatePrefs
import com.nexo.data.storage.AppInternalCoverStorage
import com.nexo.data.sync.SessionGateImpl
import com.nexo.data.update.UpdateDownloader
import com.nexo.data.update.UpdateFeedService
import com.nexo.di.modules.DatabaseModule
import com.nexo.di.modules.NetworkModule
import com.nexo.di.modules.PreferencesModule
import com.nexo.di.modules.RepositoryModule
import com.nexo.di.modules.StorageModule
import com.nexo.di.modules.UseCaseModule
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.repository.AuthRepository
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SessionGate
import com.nexo.domain.sync.SyncSettleGate
import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateNetworkGate
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.domain.usecase.UpdateReadingProgressUseCase

class AppContainer(
    context: Context,
) {
    companion object {
        private const val TAG = "AppContainer"
    }

    private val startTime = System.currentTimeMillis()

    private val appContext = context.applicationContext
    private val databaseModule = DatabaseModule(context.applicationContext)
    private val storageModule = StorageModule(context.applicationContext, databaseModule)
    private val preferencesModule = PreferencesModule(context.applicationContext)

    // NetworkModule is built BEFORE RepositoryModule so the drain scheduler can
    // resolve the (lazy, dependency-free) SessionManager at container init. The
    // two modules are otherwise independent — NetworkModule does not reference
    // RepositoryModule.
    private val networkModule = NetworkModule(context.applicationContext, databaseModule, preferencesModule)

    /**
     * Outbox drain scheduler (S6): WorkManager-backed, one-shot unique work per
     * user. Lazy so no WorkManager touch happens at container construction; the
     * scheduler also resolves WorkManager lazily on first enqueue. Declared
     * before [repositoryModule] because the module resolves it at construction —
     * a `by lazy` referenced before its delegate is initialised would NPE.
     */
    val outboxDrainScheduler: OutboxDrainScheduler by lazy {
        WorkManagerOutboxDrainScheduler(
            context = appContext,
            sessionManager = networkModule.sessionManager,
        )
    }

    private val repositoryModule =
        RepositoryModule(
            context = context.applicationContext,
            databaseModule = databaseModule,
            storageModule = storageModule,
            preferencesModule = preferencesModule,
            syncSettleGateProvider = { syncSettleGate },
            drainSchedulerProvider = { outboxDrainScheduler },
            dictionarySyncProvider = { networkModule.dictionarySyncService },
        )
    private val useCaseModule = UseCaseModule(repositoryModule, databaseModule, preferencesModule)

    // ── Eager delegation via get() — no double init ───────────────────
    val coverStorage: AppInternalCoverStorage get() = storageModule.coverStorage
    val coilImageLoader: ImageLoader get() = storageModule.coilImageLoader
    val libraryRepository: LibraryRepository get() = repositoryModule.libraryRepository
    val readerRepository: ReaderRepository get() = repositoryModule.readerRepository
    val readingStatsRepository: ReadingStatsRepository get() = repositoryModule.readingStatsRepository
    val homeRepository: HomeRepository get() = repositoryModule.homeRepository
    val dictionaryRepository: DictionaryRepository get() = repositoryModule.dictionaryRepository
    val cacheRepository: CacheRepository get() = repositoryModule.cacheRepository
    val storageRepository: StorageRepository get() = repositoryModule.storageRepository
    val readerPreferences: ReaderPreferences get() = preferencesModule.readerPreferences
    val updatePrefs: UpdatePrefs get() = preferencesModule.updatePrefs
    val readingGoalPreferences: ReadingGoalPreferences get() = preferencesModule.readingGoalPreferences
    val dailyGoalProvider: () -> Int get() = preferencesModule.dailyGoalProvider

    // ── Lazy delegation — preserves cold-start partition ───────────────
    val updateReadingProgressUseCase: UpdateReadingProgressUseCase by lazy { useCaseModule.updateReadingProgressUseCase }
    val getStatisticsUseCase: GetStatisticsUseCase by lazy { useCaseModule.getStatisticsUseCase }
    val getBookProgressUseCase: GetBookProgressUseCase by lazy { useCaseModule.getBookProgressUseCase }
    val progressReconciler: com.nexo.data.sync.ProgressReconciler by lazy { useCaseModule.progressReconciler }
    val driveOAuthSession: DriveOAuthSession by lazy { networkModule.driveOAuthSession }
    val googleDriveAuthHelper: GoogleDriveAuthHelper by lazy { networkModule.googleDriveAuthHelper }
    val driveCoordinator: DriveCoordinator by lazy { networkModule.driveCoordinator }
    val driveRemoteDataSource: StorageSyncRemoteDataSource by lazy { networkModule.driveRemoteDataSource }
    val sessionManager: SessionManager by lazy { networkModule.sessionManager }
    val authRepository: AuthRepository by lazy { networkModule.authRepository }
    val syncService: SyncService by lazy { networkModule.syncService }
    val supabaseProgressDataSource: SupabaseProgressDataSource by lazy { networkModule.supabaseProgressDataSource }
    val supabaseProgressSync: SupabaseProgressSync by lazy { networkModule.supabaseProgressSync }
    val supabaseBookCatalogDataSource: SupabaseBookCatalogDataSource by lazy { networkModule.supabaseBookCatalogDataSource }
    val supabaseBookCatalogSync: SupabaseBookCatalogSync by lazy { networkModule.supabaseBookCatalogSync }
    val dictionarySyncService: com.nexo.data.sync.DictionarySyncService by lazy { networkModule.dictionarySyncService }
    val catalogProvider: CatalogProvider by lazy { networkModule.catalogProvider }

    // SDD android-tooling-hygiene WS2a slice 4: delegated — NetworkModule builds
    // this via createConnectivityObserver, the shared factory also used by
    // HiltFoundationModule. Manual container retained until slices 5-6 migrate
    // consumers; no `hiltViewModel()` / ViewModel migration in this slice.
    val connectivityObserver: ConnectivityObserver by lazy { networkModule.connectivityObserver }
    val updateFeedService: UpdateFeedService by lazy { networkModule.updateFeedService }
    val updateNetworkGate: UpdateNetworkGate by lazy { networkModule.updateNetworkGate }
    val updateDownloader: UpdateDownloader by lazy { networkModule.updateDownloader }
    val checkForUpdatesUseCase: CheckForUpdatesUseCase by lazy {
        CheckForUpdatesUseCase(
            fetcher = networkModule.updateFeedService,
            suppression = preferencesModule.updatePrefs,
            networkGate = networkModule.updateNetworkGate,
        )
    }
    val catalogFileDownloader: CatalogFileDownloader by lazy { networkModule.catalogFileDownloader }
    val downloadAndImportBookUseCase: DownloadAndImportBookUseCase by lazy {
        DownloadAndImportBookUseCase(
            downloader = networkModule.catalogFileDownloader,
            importEpubBookUseCase = ImportEpubBookUseCase(repositoryModule.libraryRepository),
            libraryRepository = repositoryModule.libraryRepository,
            tempDir = networkModule.catalogTempDir,
        )
    }
    val addonRegistry: com.nexo.data.remote.addons.AddonRegistry by lazy { networkModule.addonRegistry }

    /** U5: ephemeral per-addon reading-link resolve for the detail sheet. */
    val addonResolveForBook: suspend (String, com.nexo.data.remote.catalog.CatalogBook) -> com.nexo.domain.access.LegalAccess
        by lazy { networkModule.addonResolveForBook }

    // ── addon-deeplink-v1: pending install deep links (B2/B3) ──────────
    // Main-scoped: onInstallUri/confirm mutate StateFlow read by the Compose
    // dialog host in NexoNavHost root.
    // SDD android-tooling-hygiene WS2b slice 5: delegated — built via
    // createInstallDeepLinkController, the shared factory also used by
    // HiltSingletonsModule. Manual container retained until slice 6 migrates
    // consumers.
    val installDeepLinkController: com.nexo.presentation.navigation.InstallDeepLinkController by lazy {
        createInstallDeepLinkController(registry = addonRegistry)
    }
    val driveColdBackupService: DriveColdBackupService by lazy { networkModule.driveColdBackupService }

    // ── sync-layer-split PR-1 foundations ────────────────────────────────
    // SessionGate is the domain-sync helper; the SyncOrchestrator that wires
    // it into a per-domain lifecycle arrives in PR-2 (and AuthViewModel
    // consumes it in PR-3). `syncState` is a sealed type, not a singleton,
    // so it is not wired here — consumers (DebugViewModel) continue reading
    // per-domain states until the orchestrator exists.
    val sessionGate: SessionGate by lazy { SessionGateImpl(sessionManager) }

    // ── sync-layer-split PR-2: SyncOrchestrator ──────────────────────────
    // Wires Drive (SyncService) + Catalog + Progress + SessionGate + the
    // shared outbox DAO into a single per-domain lifecycle handle. Consumed
    // by `AuthViewModel` in PR-3 (sign-out closes ALL Realtime channels via
    // orchestrator.stop, fixing the catalog-logout Realtime leak; sign-in
    // delegates to orchestrator.start).
    // SDD android-tooling-hygiene WS2b slice 5: delegated — built via
    // createSyncOrchestrator, the shared factory also used by
    // HiltSingletonsModule. Manual container retained until slice 6 migrates
    // consumers.
    val syncOrchestrator: SyncOrchestrator by lazy {
        createSyncOrchestrator(
            drive = syncService,
            catalog = supabaseBookCatalogSync,
            progress = supabaseProgressSync,
            dictionary = dictionarySyncService,
            gate = sessionGate,
            outboxDao = databaseModule.syncOutboxDao,
        )
    }

    /**
     * Settle signal consumed by local file cleanup: a delete waits (bounded)
     * for the aggregate sync state to leave [com.nexo.data.remote.sync.SyncState.Active].
     * Lazy so it is built only when the first delete/sweep runs.
     * SDD android-tooling-hygiene WS2b slice 5: delegated — built via
     * createSyncSettleGate, the shared factory also used by
     * HiltSingletonsModule.
     */
    val syncSettleGate: SyncSettleGate by lazy { createSyncSettleGate(syncOrchestrator) }

    internal object ReaderDependencies {
        fun updateReadingProgressUseCase(readerRepository: ReaderRepository) = UpdateReadingProgressUseCase(readerRepository)
    }

    internal object ReaderInteractionDependencies {
        fun interactionStore(
            state: kotlinx.coroutines.flow.MutableStateFlow<com.nexo.presentation.viewmodel.reader.ReaderInteractionState>,
            clearEvent: kotlinx.coroutines.flow.MutableSharedFlow<Unit>,
        ) = com.nexo.presentation.viewmodel.reader.interaction
            .InteractionStateStore(state, clearEvent)

        fun selectionManager(
            store: com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore,
            scope: kotlinx.coroutines.CoroutineScope,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ) = com.nexo.presentation.viewmodel.reader.interaction
            .SelectionManager(store, scope, dispatcher)

        fun highlightManager(
            store: com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore,
            selectionManager: com.nexo.presentation.viewmodel.reader.interaction.SelectionManager,
            readerRepository: ReaderRepository,
            scope: kotlinx.coroutines.CoroutineScope,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ) = com.nexo.presentation.viewmodel.reader.interaction.HighlightManager(
            store,
            selectionManager,
            readerRepository,
            scope,
            dispatcher,
        )

        fun annotationManager(
            store: com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore,
            selectionManager: com.nexo.presentation.viewmodel.reader.interaction.SelectionManager,
            readerRepository: ReaderRepository,
            scope: kotlinx.coroutines.CoroutineScope,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ) = com.nexo.presentation.viewmodel.reader.interaction.AnnotationManager(
            store,
            selectionManager,
            readerRepository,
            scope,
            dispatcher,
        )

        fun bookmarkManager(
            store: com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore,
            readerRepository: ReaderRepository,
            scope: kotlinx.coroutines.CoroutineScope,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ) = com.nexo.presentation.viewmodel.reader.interaction
            .BookmarkManager(store, readerRepository, scope, dispatcher)

        fun shareDictionaryManager(
            store: com.nexo.presentation.viewmodel.reader.interaction.InteractionStateStore,
            selectionManager: com.nexo.presentation.viewmodel.reader.interaction.SelectionManager,
            dictionaryRepository: DictionaryRepository?,
            scope: kotlinx.coroutines.CoroutineScope,
            onEvent: (com.nexo.presentation.UiEvent) -> Unit,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ) = com.nexo.presentation.viewmodel.reader.interaction.ShareDictionaryManager(
            store,
            selectionManager,
            dictionaryRepository,
            scope,
            onEvent,
            dispatcher,
        )
    }

    val isAuthConfigError: Boolean
        get() = BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()

    private val totalInitTime = System.currentTimeMillis() - startTime

    init {
        Log.i(TAG, "AppContainer fully initialized in ${totalInitTime}ms")
    }

    val dbInitTimeMs: Long get() = databaseModule.dbInitTimeMs
    val epubImportInitTimeMs: Long get() = repositoryModule.epubImportInitTimeMs
    val readerRepoInitTimeMs: Long get() = repositoryModule.readerRepoInitTimeMs
    val totalInitTimeMs: Long get() = totalInitTime

    val bookDao: BookDao get() = databaseModule.bookDao
    val highlightDao: HighlightDao get() = databaseModule.highlightDao
    val bookmarkDao: BookmarkDao get() = databaseModule.bookmarkDao
    val readingSessionDao: ReadingSessionDao get() = databaseModule.readingSessionDao
    val readingProgressDao: ReadingProgressDao get() = databaseModule.readingProgressDao

    fun clearAllData() {
        databaseModule.clearAllTables()
    }
}

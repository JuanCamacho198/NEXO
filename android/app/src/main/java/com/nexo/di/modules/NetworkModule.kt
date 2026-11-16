package com.nexo.di.modules

import android.content.Context
import com.nexo.BuildConfig
import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.remote.addons.AddonCatalogProvider
import com.nexo.data.remote.addons.AddonRegistry
import com.nexo.data.remote.addons.CuratedCatalogProvider
import com.nexo.data.remote.addons.KtorAddonHttpTransport
import com.nexo.data.remote.addons.PersistentAddonConsentStore
import com.nexo.data.remote.addons.catalogProvidersWithAddons
import com.nexo.data.remote.catalog.ANDROID_USER_AGENT
import com.nexo.data.remote.catalog.CatalogHttpTransport
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CompositeCatalogProvider
import com.nexo.data.remote.catalog.GoogleBooksCatalogProvider
import com.nexo.data.remote.catalog.GutendexCatalogProvider
import com.nexo.data.remote.catalog.GutendexDataSource
import com.nexo.data.remote.catalog.KtorCatalogFileDownloader
import com.nexo.data.remote.catalog.KtorCatalogHttpTransport
import com.nexo.data.remote.catalog.LiveCatalogProvider
import com.nexo.data.remote.catalog.OpenLibraryCatalogProvider
import com.nexo.data.remote.catalog.OpenLibraryDataSource
import com.nexo.data.remote.catalog.RebuildingCatalogProvider
import com.nexo.data.remote.catalog.RoomDiscoverCache
import com.nexo.data.remote.catalog.StandardEbooksCatalogProvider
import com.nexo.data.remote.catalog.StandardEbooksDataSource
import com.nexo.data.remote.catalog.androidAppIdentityOrNull
import com.nexo.data.remote.catalog.googleBooksProviderOrNull
import com.nexo.data.remote.drive.DriveCoordinator
import com.nexo.data.remote.drive.DriveOAuthSession
import com.nexo.data.remote.drive.DriveTokenApi
import com.nexo.data.remote.drive.DriveTokenStore
import com.nexo.data.remote.drive.EncryptedDriveTokenStore
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.remote.drive.InMemoryDriveTokenStore
import com.nexo.data.remote.drive.KtorAuthApi
import com.nexo.data.remote.drive.driveOAuthRedirectUri
import com.nexo.data.remote.supabase.SupabaseBookCatalogDataSource
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.data.remote.supabase.SupabaseProgressDataSource
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.DriveColdBackupService
import com.nexo.data.remote.sync.GoogleDriveSyncService
import com.nexo.data.remote.sync.OutboxCommit
import com.nexo.data.remote.sync.StorageSyncRemoteDataSource
import com.nexo.data.remote.sync.SyncService
import com.nexo.data.repository.SupabaseAuthRepository
import com.nexo.data.session.EncryptedSessionSettings
import com.nexo.data.session.InMemorySessionStore
import com.nexo.data.session.PreferencesSessionStore
import com.nexo.data.session.SessionManager
import com.nexo.data.session.SessionStore
import com.nexo.data.session.SupabaseSessionManager
import com.nexo.data.sync.DictionarySyncService
import com.nexo.data.update.AndroidUpdateNetworkGate
import com.nexo.data.update.UpdateFeedService
import com.nexo.di.createInstallDeepLinkController
import com.nexo.domain.access.LegalAccess
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.CatalogBook
import com.nexo.domain.model.CatalogFileSource
import com.nexo.domain.repository.AuthRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.navigation.InstallDeepLinkController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton
import io.github.jan.supabase.auth.SessionManager as SupabaseAuthSessionManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * HTTP timeouts shared by every OkHttp client the module builds.
     *
     * They were repeated as literals in four separate client builders; naming
     * them keeps the four in lockstep and satisfies the magic-number rule.
     */
    private const val REQUEST_TIMEOUT_MILLIS = 15_000L
    private const val CONNECT_TIMEOUT_MILLIS = 10_000L
    private const val SOCKET_TIMEOUT_MILLIS = 15_000L

    /**
     * Qualifier for the ephemeral addon reading-link resolver. A function type
     * needs a qualifier to be bindable at all; this is not a container.
     */
    const val ADDON_RESOLVE_FOR_BOOK = "addonResolveForBook"
    const val CATALOG_HTTP_CLIENT = "catalogHttpClient"
    const val UPDATE_HTTP_CLIENT = "updateHttpClient"
    const val TRANSLATION_HTTP_CLIENT = "translationHttpClient"
    const val WIKTIONARY_HTTP_CLIENT = "wiktionaryHttpClient"

    // ── Session / Drive ─────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDriveTokenStore(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): DriveTokenStore =
        runCatching { EncryptedDriveTokenStore(context, activeUserProvider) }
            .getOrElse { InMemoryDriveTokenStore() }

    @Provides
    @Singleton
    fun provideDriveTokenApi(): DriveTokenApi = KtorAuthApi(HttpClient())

    @Provides
    @Singleton
    fun provideDriveOAuthSession(
        tokenStore: DriveTokenStore,
        tokenApi: DriveTokenApi,
    ): DriveOAuthSession =
        DriveOAuthSession(
            clientId = BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID,
            redirectUri = driveOAuthRedirectUri(BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID),
            tokenStore = tokenStore,
            tokenApi = tokenApi,
        )

    /**
     * Singleton on purpose: it carries the in-flight PKCE verifier and the
     * redirect outcome across process-wide deep links. `MainActivity.onNewIntent`
     * completes the attempt that some screen started, so an Activity-scoped
     * instance would lose the pending auth on every config change.
     */
    @Provides
    @Singleton
    fun provideGoogleDriveAuthHelper(
        @ApplicationContext context: Context,
        session: DriveOAuthSession,
    ): GoogleDriveAuthHelper = GoogleDriveAuthHelper(context = context, session = session)

    @Provides
    @Singleton
    fun provideDriveCoordinator(
        @ApplicationContext context: Context,
        tokenStore: DriveTokenStore,
        tokenApi: DriveTokenApi,
    ): DriveCoordinator =
        DriveCoordinator(
            context = context,
            tokenStore = tokenStore,
            tokenApi = tokenApi,
            clientId = BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID,
        )

    @Provides
    @Singleton
    fun provideDriveRemoteDataSource(
        driveCoordinator: DriveCoordinator,
    ): StorageSyncRemoteDataSource = driveCoordinator.buildDataSource()

    @Provides
    @Singleton
    fun provideSessionStore(
        @ApplicationContext context: Context,
    ): SessionStore =
        runCatching { PreferencesSessionStore(context) }
            .getOrElse { InMemorySessionStore() }

    @Provides
    @Singleton
    fun provideSessionManager(
        sessionStore: SessionStore,
    ): SessionManager = SupabaseSessionManager(sessionStore)

    /**
     * Encrypted supabase-kt token store.
     *
     * Calls [SupabaseClientProvider.configureSessionManager] before returning:
     * the configuration must land before the first `SupabaseClientProvider.client`
     * access, otherwise token persistence silently stays plaintext. Hilt builds
     * this before any provider that consumes the client, so the ordering the
     * manual container guaranteed in its constructor is preserved here.
     */
    @Provides
    @Singleton
    fun provideSupabaseTokenSessionManager(
        @ApplicationContext context: Context,
    ): SupabaseAuthSessionManager {
        val manager =
            runCatching {
                SettingsSessionManager(
                    EncryptedSessionSettings.create(context, BuildConfig.SUPABASE_URL),
                )
            }.getOrElse { MemorySessionManager() }
        SupabaseClientProvider.configureSessionManager(manager)
        return manager
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        sessionManager: SessionManager,
    ): AuthRepository = SupabaseAuthRepository(sessionManager = sessionManager)

    // ── Sync ────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSyncService(
        @ApplicationContext context: Context,
        outboxDao: SyncOutboxDao,
        bookDao: BookDao,
        mappingDao: SyncFileMappingDao,
        highlightDao: HighlightDao,
        bookmarkDao: BookmarkDao,
        sessionManager: SessionManager,
        remoteDataSource: StorageSyncRemoteDataSource,
        driveCoordinator: DriveCoordinator,
    ): SyncService =
        GoogleDriveSyncService(
            outboxDao = outboxDao,
            bookDao = bookDao,
            mappingDao = mappingDao,
            highlightDao = highlightDao,
            bookmarkDao = bookmarkDao,
            sessionManager = sessionManager,
            remoteDataSource = remoteDataSource,
            localBooksDir = context.filesDir.resolve("books"),
            isEnabled = driveCoordinator::isEnabled,
            tokenRefresher = { driveCoordinator.refreshAccessToken() },
            diagnosticError =
                AppError(
                    category = ErrorCategory.CONFIG_ERROR,
                    code = "SYNC_DRIVE_NOT_AUTHORIZED",
                    message = "Google Drive not authorized. Authorize in Settings → Data & Storage.",
                    component = "SyncService",
                ),
        )

    @Provides
    @Singleton
    fun provideSupabaseProgressDataSource(): SupabaseProgressDataSource = SupabaseProgressDataSource()

    @Provides
    @Singleton
    fun provideOutboxCommit(
        outboxDao: SyncOutboxDao,
    ): OutboxCommit = OutboxCommit(outboxDao)

    @Provides
    @Singleton
    fun provideSupabaseProgressSync(
        outboxDao: SyncOutboxDao,
        bookDao: BookDao,
        readingProgressDao: ReadingProgressDao,
        bookmarkDao: BookmarkDao,
        highlightDao: HighlightDao,
        readingSessionDao: ReadingSessionDao,
        sessionManager: SessionManager,
        dataSource: SupabaseProgressDataSource,
        outboxCommit: OutboxCommit,
    ): SupabaseProgressSync =
        SupabaseProgressSync(
            outboxDao = outboxDao,
            bookDao = bookDao,
            readingProgressDao = readingProgressDao,
            bookmarkDao = bookmarkDao,
            highlightDao = highlightDao,
            readingSessionDao = readingSessionDao,
            sessionManager = sessionManager,
            dataSource = dataSource,
            outboxCommit = outboxCommit,
        )

    @Provides
    @Singleton
    fun provideDictionarySyncService(
        dictionaryWordDao: DictionaryWordDao,
        sessionManager: SessionManager,
    ): DictionarySyncService =
        DictionarySyncService(
            dao = dictionaryWordDao,
            sessionManager = sessionManager,
        )

    @Provides
    @Singleton
    fun provideSupabaseBookCatalogDataSource(): SupabaseBookCatalogDataSource = SupabaseBookCatalogDataSource()

    @Provides
    @Singleton
    fun provideSupabaseBookCatalogSync(
        @ApplicationContext context: Context,
        outboxDao: SyncOutboxDao,
        bookDao: BookDao,
        sessionManager: SessionManager,
        dataSource: SupabaseBookCatalogDataSource,
        remoteDataSource: StorageSyncRemoteDataSource,
        driveCoordinator: DriveCoordinator,
        progressDataSource: SupabaseProgressDataSource,
        outboxCommit: OutboxCommit,
    ): SupabaseBookCatalogSync =
        SupabaseBookCatalogSync(
            outboxDao = outboxDao,
            bookDao = bookDao,
            sessionManager = sessionManager,
            dataSource = dataSource,
            remoteDataSource = remoteDataSource,
            driveTokenRefresher = { driveCoordinator.refreshAccessToken() },
            localBooksDir = context.filesDir.resolve("books"),
            progressDataSource = progressDataSource,
            outboxCommit = outboxCommit,
        )

    @Provides
    @Singleton
    fun provideDriveColdBackupService(
        remoteDataSource: StorageSyncRemoteDataSource,
        bookDao: BookDao,
        readingProgressDao: ReadingProgressDao,
        highlightDao: HighlightDao,
        bookmarkDao: BookmarkDao,
        readingSessionDao: ReadingSessionDao,
        bookCatalogDataSource: SupabaseBookCatalogDataSource,
        progressDataSource: SupabaseProgressDataSource,
        sessionManager: SessionManager,
    ): DriveColdBackupService =
        DriveColdBackupService(
            remoteDataSource = remoteDataSource,
            bookDao = bookDao,
            readingProgressDao = readingProgressDao,
            highlightDao = highlightDao,
            bookmarkDao = bookmarkDao,
            readingSessionDao = readingSessionDao,
            bookCatalogDataSource = bookCatalogDataSource,
            progressDataSource = progressDataSource,
            sessionManager = sessionManager,
        )

    // ── Catalog ─────────────────────────────────────────────────────────

    /**
     * Dedicated catalog client: separate OkHttp stack (timeouts, JSON,
     * identified UA, HTTPS-only by constant base URLs + network_security_config)
     * so public catalog traffic never shares the Drive/Supabase client.
     * Engine is OkHttp (not CIO): CIO failed to reach the catalog on real
     * devices while the OkHttp-backed clients worked.
     */
    @Provides
    @Singleton
    @Named(CATALOG_HTTP_CLIENT)
    fun provideCatalogHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, ANDROID_USER_AGENT)
            }
        }

    @Provides
    @Singleton
    fun provideCatalogTransport(
        @Named(CATALOG_HTTP_CLIENT) client: HttpClient,
    ): CatalogHttpTransport = KtorCatalogHttpTransport(client)

    @Provides
    @Singleton
    fun provideCatalogFileDownloader(
        @Named(CATALOG_HTTP_CLIENT) client: HttpClient,
    ): CatalogFileSource = KtorCatalogFileDownloader(client)

    @Provides
    @Singleton
    fun provideAddonTransport(
        @Named(CATALOG_HTTP_CLIENT) catalogHttpClient: HttpClient,
    ): KtorAddonHttpTransport = KtorAddonHttpTransport(catalogHttpClient)

    @Provides
    @Singleton
    fun provideAddonRegistry(
        @ApplicationContext context: Context,
        installedAddonDao: AddonDao,
        transport: KtorAddonHttpTransport,
    ): AddonRegistry =
        AddonRegistry(
            installedAddonDao,
            transport,
            // U5: durable consent (survives process restart); delivers the
            // persistence deferred from U4's in-memory default.
            PersistentAddonConsentStore(context),
        )

    @Provides
    @Singleton
    fun provideGutendexDataSource(
        transport: CatalogHttpTransport,
    ): GutendexDataSource = GutendexDataSource(transport)

    @Provides
    @Singleton
    fun provideOpenLibraryDataSource(
        transport: CatalogHttpTransport,
    ): OpenLibraryDataSource = OpenLibraryDataSource(transport)

    @Provides
    @Singleton
    fun provideStandardEbooksDataSource(
        transport: CatalogHttpTransport,
    ): StandardEbooksDataSource = StandardEbooksDataSource(transport)

    /**
     * Google Books (U2, fail-closed): null when `GOOGLE_BOOKS_KEY` is
     * absent/blank so the composite fan-out simply never sees the source.
     * The key is restricted to Android apps (package + signing SHA-1), so the
     * app identity headers travel with every request.
     */
    @Provides
    @Singleton
    fun provideGoogleBooksCatalogProvider(
        @ApplicationContext context: Context,
        transport: CatalogHttpTransport,
    ): GoogleBooksCatalogProvider? {
        val identity = androidAppIdentityOrNull(context)
        return googleBooksProviderOrNull(
            transport,
            BuildConfig.GOOGLE_BOOKS_KEY,
            androidPackage = identity?.packageName,
            androidCert = identity?.certSha1,
        )
    }

    /**
     * Live composite: rebuilds from installed addon rows whenever the registry
     * mutates, so addon sources start or stop contributing immediately.
     */
    @Provides
    @Singleton
    fun provideRebuildingCatalogProvider(
        @ApplicationContext context: Context,
        transport: CatalogHttpTransport,
        gutendex: GutendexDataSource,
        openLibrary: OpenLibraryDataSource,
        googleBooks: GoogleBooksCatalogProvider?,
        standardEbooks: StandardEbooksDataSource,
        discoverCacheDao: DiscoverCacheDao,
        addonTransport: KtorAddonHttpTransport,
        addonRegistry: AddonRegistry,
    ): RebuildingCatalogProvider {
        val provider =
            RebuildingCatalogProvider {
                CompositeCatalogProvider(
                    catalogProvidersWithAddons(
                        builtIns =
                            listOfNotNull(
                                GutendexCatalogProvider(gutendex),
                                OpenLibraryCatalogProvider(openLibrary),
                                // Fail-closed: absent/blank GOOGLE_BOOKS_KEY ⇒ null ⇒ omitted.
                                googleBooks,
                                // Before the curated bundle so the real source wins the
                                // source-id dedupe: the bundle lists the same
                                // `builtin:standard-ebooks` id as CURATED, and first
                                // occurrence wins in `listSources()`.
                                StandardEbooksCatalogProvider(standardEbooks),
                            ),
                        curated = CuratedCatalogProvider(context),
                        installedAddons = addonRegistry.listInstalled(),
                        addonTransport = addonTransport,
                        // U4: providers share the registry consent store so a
                        // recorded disclosure consent unblocks resolveAccess.
                        addonConsent = addonRegistry.consent,
                    ),
                    cache = RoomDiscoverCache(discoverCacheDao),
                )
            }
        addonRegistry.addOnChangedListener { provider.invalidate() }
        return provider
    }

    @Provides
    @Singleton
    fun provideCatalogProvider(
        rebuilding: RebuildingCatalogProvider,
    ): CatalogProvider = LiveCatalogProvider(rebuilding)

    /**
     * U5: resolves one installed addon's reading links for a book through an
     * ephemeral provider over its manifest. Fail-closed: unknown addon or
     * missing disclosure consent resolves to an empty [LegalAccess] with zero I/O.
     */
    @Provides
    @Singleton
    fun provideAddonAccessResolver(
        addonRegistry: AddonRegistry,
        addonTransport: KtorAddonHttpTransport,
    ): com.nexo.domain.access.AddonAccessResolver =
        object : com.nexo.domain.access.AddonAccessResolver {
            override suspend fun resolve(
                addonId: String,
                book: CatalogBook,
            ): LegalAccess {
                val row = addonRegistry.listInstalled().find { it.id == addonId }
                return if (row == null || !addonRegistry.hasAddonConsent(addonId)) {
                    LegalAccess(book.id, false, null, emptyList())
                } else {
                    AddonCatalogProvider(
                        row.manifest,
                        addonId,
                        addonTransport,
                        consent = addonRegistry.consent,
                    ).resolveAccess(book)
                }
            }
        }

    // ── Connectivity ────────────────────────────────────────────────────

    // App-lifetime connectivity observer is provided by HiltFoundationModule.

    // ── App self-update ─────────────────────────────────────────────────

    @Provides
    @Singleton
    @Named(UPDATE_HTTP_CLIENT)
    fun provideUpdateHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, ANDROID_USER_AGENT)
            }
        }

    @Provides
    @Singleton
    fun provideUpdateFeedService(
        @Named(UPDATE_HTTP_CLIENT) client: HttpClient,
    ): UpdateFeedService = UpdateFeedService(client, BuildConfig.UPDATE_FEED_URL)

    @Provides
    @Singleton
    fun provideUpdateNetworkGate(
        @ApplicationContext context: Context,
        connectivityObserver: ConnectivityObserver,
    ): com.nexo.domain.update.UpdateNetworkGate = AndroidUpdateNetworkGate(context, connectivityObserver)

    @Provides
    @Singleton
    fun provideDownloadAndImportBookUseCase(
        @ApplicationContext context: Context,
        @Named(CATALOG_HTTP_CLIENT) client: HttpClient,
        importEpubBookUseCase: com.nexo.domain.usecase.ImportEpubBookUseCase,
        libraryRepository: com.nexo.domain.repository.LibraryRepository,
    ): com.nexo.domain.usecase.DownloadAndImportBookUseCase =
        com.nexo.domain.usecase.DownloadAndImportBookUseCase(
            downloader = KtorCatalogFileDownloader(client),
            importEpubBookUseCase = importEpubBookUseCase,
            libraryRepository = libraryRepository,
            tempDir = java.io.File(context.cacheDir, "catalog"),
            booksDir = java.io.File(context.filesDir, "books"),
        )

    // ── Reader-selection translation + definition ───────────────────────

    @Provides
    @Singleton
    @Named(TRANSLATION_HTTP_CLIENT)
    fun provideTranslationHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, com.nexo.data.remote.translation.NEXO_TRANSLATION_USER_AGENT)
            }
        }

    @Provides
    @Singleton
    fun provideTranslationClient(
        @Named(TRANSLATION_HTTP_CLIENT) client: HttpClient,
    ): com.nexo.data.remote.translation.MyMemoryTranslationClient =
        com.nexo.data.remote.translation.MyMemoryTranslationClient(
            com.nexo.data.remote.translation
                .KtorTranslationHttpTransport(client),
        )

    @Provides
    @Singleton
    fun provideTranslationRepository(
        client: com.nexo.data.remote.translation.MyMemoryTranslationClient,
        cacheDao: com.nexo.data.local.dao.TranslationCacheDao,
        activeUserProvider: ActiveUserProvider,
    ): com.nexo.domain.repository.TranslationRepository =
        com.nexo.data.repository.RoomTranslationRepository(
            client = client,
            cacheDao = cacheDao,
            activeUser = activeUserProvider,
        )

    @Provides
    @Singleton
    @Named(WIKTIONARY_HTTP_CLIENT)
    fun provideWiktionaryHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
            defaultRequest {
                header(
                    HttpHeaders.UserAgent,
                    com.nexo.domain.definition.NEXO_WIKTIONARY_USER_AGENT,
                )
            }
        }

    @Provides
    @Singleton
    fun provideWiktionaryClient(
        @Named(WIKTIONARY_HTTP_CLIENT) client: HttpClient,
    ): com.nexo.data.remote.wiktionary.WiktionaryClient =
        com.nexo.data.remote.wiktionary.WiktionaryClient(
            com.nexo.data.remote.wiktionary
                .KtorWiktionaryHttpTransport(client),
        )

    @Provides
    @Singleton
    fun provideDefinitionRepository(
        wiktionaryClient: com.nexo.data.remote.wiktionary.WiktionaryClient,
        translationClient: com.nexo.data.remote.translation.MyMemoryTranslationClient,
        definitionCacheDao: com.nexo.data.local.dao.DefinitionCacheDao,
        activeUserProvider: ActiveUserProvider,
    ): com.nexo.domain.repository.DefinitionRepository =
        com.nexo.data.repository.CachedDefinitionRepository(
            delegate =
                com.nexo.data.repository.RemoteDefinitionRepository(
                    wiktionaryClient = wiktionaryClient,
                    translationClient = translationClient,
                ),
            cacheDao = definitionCacheDao,
            activeUser = activeUserProvider,
        )

    @Provides
    @Singleton
    fun provideReadingGoalRemoteDataSource(): com.nexo.data.sync.ReadingGoalRemoteDataSource =
        com.nexo.data.remote.supabase
            .SupabaseReadingGoalDataSource()

    @Provides
    @Singleton
    fun provideReadingGoalRepository(
        local: com.nexo.data.session.ReadingGoalPreferences,
        remote: com.nexo.data.sync.ReadingGoalRemoteDataSource,
        activeUserProvider: ActiveUserProvider,
    ): com.nexo.data.repository.ReadingGoalRepository =
        com.nexo.data.repository.ReadingGoalRepository(
            local = local,
            remote = remote,
            activeUserIdProvider = { activeUserProvider.currentUserId() },
        )

    // ── Deep links ──────────────────────────────────────────────────────

    /**
     * Singleton so the install-flow `StateFlow` survives configuration changes
     * and the Compose dialog host keeps observing one instance.
     */
    @Provides
    @Singleton
    fun provideInstallDeepLinkController(
        addonRegistry: AddonRegistry,
    ): InstallDeepLinkController = createInstallDeepLinkController(registry = addonRegistry)
}

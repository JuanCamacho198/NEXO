package com.nexo.presentation.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.storage.CoverStorage
import com.nexo.di.AppContainer
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.presentation.debug.DebugViewModel
import com.nexo.presentation.feature.discover.DiscoverSectionViewModel
import com.nexo.presentation.feature.discover.DiscoverSectionViewModelFactory
import com.nexo.presentation.screen.settings.AddonSettingsViewModelFactory
import com.nexo.presentation.viewmodel.AddonSettingsViewModel
import com.nexo.presentation.viewmodel.AuthViewModel
import com.nexo.presentation.viewmodel.BookDetailViewModel
import com.nexo.presentation.viewmodel.DictionaryViewModel
import com.nexo.presentation.viewmodel.DiscoverViewModel
import com.nexo.presentation.viewmodel.DiscoverViewModelFactory
import com.nexo.presentation.viewmodel.EditBookMetadataViewModel
import com.nexo.presentation.viewmodel.HighlightsViewModel
import com.nexo.presentation.viewmodel.HomeViewModel
import com.nexo.presentation.viewmodel.LibraryViewModel
import com.nexo.presentation.viewmodel.PerformanceViewModel
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.ReaderViewModelFactory
import com.nexo.presentation.viewmodel.SettingsDevicesViewModel
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.presentation.viewmodel.StorageViewModel
import com.nexo.presentation.viewmodel.UpdateViewModel
import kotlinx.coroutines.Dispatchers

/**
 * Holder grouping the host-scoped ViewModels.
 *
 * Mirrors AppContainer.ReaderDependencies facade pattern (PR #1/2):
 * keeps factory wiring host-local without leaking AppContainer module split (PR #4).
 * ViewModels are created once in the host via [rememberNavHostViewModels] and
 * injected into feature NavGraphBuilders — builders never call viewModel() inside composable.
 *
 * This file is the single designated provider for production ViewModel
 * resolution: every `viewModel()`/`hiltViewModel()` call in `src/main` lives
 * here, every direct `*ViewModel(...)` construction lives here, and callers
 * receive the instance as a parameter. Enforced over the whole production
 * source set by `NoInlineViewModelResolutionTest` (no inline
 * `viewModel()`/`hiltViewModel()`) and `DirectViewModelConstructionTest` (no
 * direct construction inside a composable body).
 */
internal data class ViewModelProviders(
    val library: LibraryViewModel,
    val reader: ReaderViewModel,
    val highlights: HighlightsViewModel,
    val statistics: StatisticsViewModel,
    val auth: AuthViewModel,
    val home: HomeViewModel,
    val debug: DebugViewModel,
    val discover: DiscoverViewModel,
)

/**
 * Creates and remembers all host-scoped ViewModels — the single-sourced path.
 *
 * Plain VMs use `hiltViewModel()`; Reader/Discover keep assisted factories.
 * selectedBookId is still host-owned and passed to Reader via defaultBookId at creation time,
 * then kept in sync via write lambdas (see BookDetail/Reader graphs).
 */
@Composable
internal fun rememberNavHostViewModels(
    appContainer: AppContainer,
    selectedBookId: String,
): ViewModelProviders {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application

    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val readerViewModel: ReaderViewModel =
        viewModel(
            factory =
                ReaderViewModelFactory(
                    application = application,
                    readerRepository = appContainer.readerRepository,
                    readingStatsRepository = appContainer.readingStatsRepository,
                    readerPreferences = appContainer.readerPreferences,
                    defaultBookId = selectedBookId,
                    dictionaryRepository = appContainer.dictionaryRepository,
                    libraryRepository = appContainer.libraryRepository,
                    supabaseProgressSync = appContainer.supabaseProgressSync,
                ),
        )

    val highlightsViewModel: HighlightsViewModel = hiltViewModel()

    val statisticsViewModel: StatisticsViewModel = hiltViewModel()

    val authViewModel: AuthViewModel = hiltViewModel()

    val homeViewModel: HomeViewModel = hiltViewModel()

    val debugViewModel: DebugViewModel = hiltViewModel()

    val discoverViewModel: DiscoverViewModel =
        viewModel(
            factory =
                DiscoverViewModelFactory(
                    catalogProvider = appContainer.catalogProvider,
                    connectivityObserver = appContainer.connectivityObserver,
                    downloadAndImportBookUseCase = appContainer.downloadAndImportBookUseCase,
                    registerAddonChangeListener = { listener ->
                        appContainer.addonRegistry.addOnChangedListener(listener)
                    },
                    addonConsent = { addonId -> appContainer.addonRegistry.hasAddonConsent(addonId) },
                    onAddonConsentChange = { addonId, granted ->
                        if (granted) {
                            appContainer.addonRegistry.recordAddonConsent(addonId)
                        } else {
                            appContainer.addonRegistry.revokeAddonConsent(addonId)
                        }
                    },
                    addonResolve = { addonId, book -> appContainer.addonResolveForBook(addonId, book) },
                ),
        )

    return ViewModelProviders(
        library = libraryViewModel,
        reader = readerViewModel,
        highlights = highlightsViewModel,
        statistics = statisticsViewModel,
        auth = authViewModel,
        home = homeViewModel,
        debug = debugViewModel,
        discover = discoverViewModel,
    )
}

/**
 * Resolves the route-scoped [DiscoverSectionViewModel].
 *
 * Section VMs are scoped to the navigation back-stack entry and depend on the
 * route's `sectionTitle`/`sort`/`sourceId` arguments, so they cannot live in the
 * host-scoped [rememberNavHostViewModels] holder. Resolution still stays
 * single-sourced in this file — [com.nexo.presentation.navigation.feature.discoverGraph]
 * never calls `viewModel()` itself (enforced by NoInlineViewModelResolutionTest).
 */
@Composable
internal fun rememberDiscoverSectionViewModel(
    catalogProvider: CatalogProvider,
    sectionTitle: String,
    sort: CatalogFeaturedSort?,
    sourceId: String?,
    downloadAndImportBookUseCase: DownloadAndImportBookUseCase?,
): DiscoverSectionViewModel =
    viewModel(
        factory =
            DiscoverSectionViewModelFactory(
                catalogProvider = catalogProvider,
                sectionTitle = sectionTitle,
                sort = sort,
                sourceId = sourceId,
                downloadAndImportBookUseCase = downloadAndImportBookUseCase,
            ),
    )

/**
 * Resolves the route-scoped [BookDetailViewModel] for one `bookId`.
 *
 * Book-detail VMs depend on the route's `bookId` plus the app-scoped library
 * repository, so they cannot live in the host-scoped [rememberNavHostViewModels]
 * holder. Resolution still stays single-sourced in this file —
 * [com.nexo.presentation.navigation.feature.bookDetailGraph] resolves here
 * and passes the instance in; it never calls `viewModel()` itself.
 */
@Composable
internal fun rememberBookDetailViewModel(
    bookId: String,
    libraryRepository: LibraryRepository,
): BookDetailViewModel =
    viewModel(
        factory = BookDetailViewModel.Factory(bookId, libraryRepository),
    )

/**
 * Resolves the route-scoped [EditBookMetadataViewModel] for one `bookId`.
 *
 * The factory needs the route's `onSaved` callback and the application context
 * (cover storage), so the route cannot create it from the host holder. The
 * settings/book-detail graph resolves here and passes the instance in.
 */
@Composable
internal fun rememberEditBookMetadataViewModel(
    bookId: String,
    libraryRepository: LibraryRepository,
    coverStorage: CoverStorage,
    onSaved: () -> Unit,
): EditBookMetadataViewModel {
    val context = LocalContext.current
    return viewModel(
        factory =
            EditBookMetadataViewModel.Factory(
                bookId = bookId,
                libraryRepository = libraryRepository,
                coverStorage = coverStorage,
                appContext = context.applicationContext,
                onSaved = onSaved,
            ),
    )
}

/**
 * Resolves the debug-only [PerformanceViewModel] for the settings sub-page.
 *
 * Created inside the nested settings NavHost destination, so it stays scoped to
 * the performance back-stack entry. Resolution stays single-sourced here; the
 * settings screen passes the instance in.
 */
@Composable
internal fun rememberPerformanceViewModel(): PerformanceViewModel {
    val context = LocalContext.current
    return viewModel(
        factory =
            PerformanceViewModel.Factory(
                context.applicationContext as android.app.Application,
            ),
    )
}

/**
 * Resolves the [AddonSettingsViewModel] for the addon-management route.
 *
 * The registry-backed factory also needs the route's error channel, so the
 * caller passes `onError` in; the route keeps owning the snackbar/error state.
 * Resolution stays single-sourced here — the route never calls `viewModel()`.
 */
@Composable
internal fun rememberAddonSettingsViewModel(
    registry: AddonRegistryLike,
    onError: (String) -> Unit,
    hasConsent: (String) -> Boolean,
    onConsentChange: (String, Boolean) -> Unit,
): AddonSettingsViewModel =
    viewModel(
        factory =
            AddonSettingsViewModelFactory(
                registry = registry,
                onError = onError,
                hasConsent = hasConsent,
                onConsentChange = onConsentChange,
            ),
    )

/**
 * Resolves the Settings-screen [DictionaryViewModel].
 *
 * Repository-backed and screen-scoped, so it is `remember`ed on the repository
 * key inside the settings screen — exactly as the inline `remember {}` did
 * before S14. The nullable repository keeps the nullable-ViewModel contract:
 * a null repository resolves to a null ViewModel and the dictionary route stays
 * unrendered.
 */
@Composable
internal fun rememberDictionaryViewModel(dictionaryRepository: DictionaryRepository?): DictionaryViewModel? =
    remember(dictionaryRepository) {
        dictionaryRepository?.let { DictionaryViewModel(it) }
    }

/**
 * Resolves the Settings-screen [StorageViewModel].
 *
 * Screen-scoped and repository-backed: `remember`ed on the three repository
 * keys, and only built once all three are present — the same nullability
 * contract the inline `remember {}` had before S14.
 */
@Composable
internal fun rememberStorageViewModel(
    storageRepository: StorageRepository?,
    cacheRepository: CacheRepository?,
    libraryRepository: LibraryRepository?,
): StorageViewModel? =
    remember(storageRepository, cacheRepository, libraryRepository) {
        if (storageRepository != null && cacheRepository != null && libraryRepository != null) {
            StorageViewModel(storageRepository, cacheRepository, libraryRepository, Dispatchers.Main)
        } else {
            null
        }
    }

/**
 * Resolves the app-update self-check [UpdateViewModel] (SDD app-auto-update).
 *
 * App-scoped and container-backed: `remember`ed on the container key, exactly
 * like the inline pattern before S14, so startup check (root host) and the
 * About manual row share one instance. A disabled feed URL still resolves —
 * the ViewModel reports version-display-only and never checks.
 */
@Composable
internal fun rememberUpdateViewModel(appContainer: AppContainer): UpdateViewModel =
    remember(appContainer) {
        UpdateViewModel(
            appContainer.checkForUpdatesUseCase,
            appContainer.updatePrefs,
            appContainer.updateNetworkGate,
            appContainer.updateDownloader,
            com.nexo.BuildConfig.VERSION_CODE,
            Dispatchers.Main,
            { System.currentTimeMillis() },
        )
    }

/**
 * Resolves the devices sub-page [SettingsDevicesViewModel] for one `userId`.
 *
 * `remember`ed on the user id inside the `SettingsDevices` destination, so it
 * is re-created only when the signed-in user changes — the same lifetime the
 * inline `remember {}` had before S14.
 */
@Composable
internal fun rememberSettingsDevicesViewModel(userId: String?): SettingsDevicesViewModel? {
    val context = LocalContext.current
    return remember(userId) {
        userId?.let { id ->
            SettingsDevicesViewModel(
                application = context.applicationContext as Application,
                userId = id,
            )
        }
    }
}

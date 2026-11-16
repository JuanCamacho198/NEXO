package com.nexo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nexo.data.remote.addons.AddonRegistry
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.SyncService
import com.nexo.data.repository.NotificationRepository
import com.nexo.data.repository.ReadingGoalRepository
import com.nexo.data.session.AppThemePreferences
import com.nexo.data.session.DefinitionPreferences
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.TranslationPreferences
import com.nexo.data.session.UpdatePrefs
import com.nexo.data.storage.CoverStorage
import com.nexo.data.update.UpdateDownloader
import com.nexo.debug.CrashContext
import com.nexo.debug.CrashNotificationHelper
import com.nexo.debug.DebugLog
import com.nexo.debug.DebugPrefs
import com.nexo.debug.DebugStateHolder
import com.nexo.debug.FeedbackActivity
import com.nexo.debug.FeedbackPersistence
import com.nexo.domain.access.AddonAccessResolver
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.model.ThemeMode
import com.nexo.domain.notification.NotificationStore
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.repository.TranslationHistoryRepository
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateNetworkGate
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.presentation.navigation.DeepLinkSchemes
import com.nexo.presentation.navigation.InstallDeepLinkController
import com.nexo.presentation.navigation.InstallDeepLinkParser
import com.nexo.presentation.navigation.NexoNavHost
import com.nexo.presentation.notification.NotificationToastBus
import com.nexo.presentation.screen.readium.evictRestoredReadiumNavigatorFragments
import com.nexo.presentation.screen.readium.installReadiumDummyFragmentFactories
import com.nexo.presentation.viewmodel.AuthViewModel
import com.nexo.ui.theme.NexoTheme
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.handleDeeplinks
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var installDeepLinkController: InstallDeepLinkController

    @Inject lateinit var googleDriveAuthHelper: GoogleDriveAuthHelper

    @Inject lateinit var activeUserProvider: ActiveUserProvider

    @Inject lateinit var libraryRepository: LibraryRepository

    @Inject lateinit var readerRepository: ReaderRepository

    @Inject lateinit var readingStatsRepository: ReadingStatsRepository

    @Inject lateinit var readerPreferences: ReaderPreferences

    @Inject lateinit var dictionaryRepository: DictionaryRepository

    @Inject lateinit var supabaseProgressSync: SupabaseProgressSync

    @Inject lateinit var translationRepository: TranslationRepository

    @Inject lateinit var translationPreferences: TranslationPreferences

    @Inject lateinit var definitionRepository: DefinitionRepository

    @Inject lateinit var definitionPreferences: DefinitionPreferences

    @Inject lateinit var catalogProvider: CatalogProvider

    @Inject lateinit var connectivityObserver: ConnectivityObserver

    @Inject lateinit var downloadAndImportBookUseCase: DownloadAndImportBookUseCase

    @Inject lateinit var addonRegistry: AddonRegistry

    @Inject lateinit var addonAccessResolver: AddonAccessResolver

    @Inject lateinit var syncService: SyncService

    @Inject lateinit var notificationStore: NotificationStore

    @Inject lateinit var notificationToastBus: NotificationToastBus

    @Inject lateinit var notificationRepository: NotificationRepository

    @Inject lateinit var getStatisticsUseCase: GetStatisticsUseCase

    @Inject lateinit var readingGoalRepository: ReadingGoalRepository

    @Inject lateinit var readingGoalPreferences: ReadingGoalPreferences

    @Inject lateinit var storageRepository: StorageRepository

    @Inject lateinit var cacheRepository: CacheRepository

    @Inject lateinit var translationHistoryRepository: TranslationHistoryRepository

    @Inject lateinit var notificationPreferencesStore: NotificationPreferencesStore

    @Inject lateinit var coverStorage: CoverStorage

    @Inject lateinit var checkForUpdatesUseCase: CheckForUpdatesUseCase

    @Inject lateinit var updatePrefs: UpdatePrefs

    @Inject lateinit var updateNetworkGate: UpdateNetworkGate

    @Inject lateinit var updateDownloader: UpdateDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate: the FragmentManager restores saved
        // navigator fragments inside super.onCreate (attachController) and needs
        // a factory able to construct them. See
        // installReadiumDummyFragmentFactories for the full rationale.
        installReadiumDummyFragmentFactories()

        super.onCreate(savedInstanceState)

        // Any navigator fragment restored from saved state is a Readium
        // placeholder that must leave the screen before onResume.
        evictRestoredReadiumNavigatorFragments()

        // Addon install deep links (nexo://install?url=..., legacy
        // nextpage://install?url=...) are checked FIRST: install URIs are never
        // auth URIs, so supabase handleDeeplinks is skipped for them entirely
        // (spec REQ routing order). Invalid install links (missing/non-https
        // url) also route here so the controller can surface the https-required
        // error dialog (verify D1).
        if (InstallDeepLinkParser.isInstallUri(intent?.data)) {
            installDeepLinkController.onInstallUri(intent?.data)
        } else {
            // Email-confirmation / OAuth deep links (nexo://auth/..., legacy
            // nextpage://auth/...). supabase-kt parses the fragment and imports
            // the signup session synchronously; must run BEFORE the AuthViewModel
            // restores the session so the confirmed account is picked up on cold
            // start.
            handleAuthDeepLink(intent)
        }

        // Native splash (Android 12+ via core-splashscreen): the NEXO logo
        // renders instantly at launch. Keep it on screen until the auth session
        // finishes restoring so there is no flash of an empty screen. The
        // AuthViewModel is shared with the NavHost through the Activity's
        // ViewModelStore (Hilt scopes both to this Activity = same instance).
        val splashScreen = installSplashScreen()
        val authViewModel: AuthViewModel by viewModels()
        splashScreen.setKeepOnScreenCondition {
            authViewModel.uiState.value.isCheckingSession
        }

        // Debug-only: capture pending crash from previous run + ensure the debug
        // crash channel exists.
        if (BuildConfig.DEBUG && DebugPrefs.isEnabled(this)) {
            CrashNotificationHelper.ensureChannel(this)
            CrashNotificationHelper.showCrashNotificationIfAny(this)
        }

        // POST_NOTIFICATIONS is deliberately NOT requested here. Asking the OS
        // permission dialog on app open asks before the user knows what it buys
        // them, and Android 13+ never shows it twice — one cold open would spend
        // the single chance. The request now lives at the end of onboarding
        // (OnboardingNotificationsScreen), after the goal, where the screen sells
        // the streak benefit first. Denial never blocks the app: the in-app tray
        // and toasts keep working and only the OS surface is unavailable.

        // Next-launch prompt (PR4 / task 4.3b): if the previous run captured
        // a crash and persisted the Sentry eventId but the user never dismissed
        // or sent feedback, open the feedback sheet. Runs in BOTH debug and
        // release builds (no DebugPrefs gate) — the design is that real users
        // hit this path too. The dismiss-once check lives in FeedbackPersistence.
        maybeShowFeedbackSheet()

        setContent {
            val appThemePrefs = remember { AppThemePreferences(this@MainActivity) }
            var appThemeMode by remember { mutableStateOf(appThemePrefs.load()) }

            val darkTheme =
                when (appThemeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }

            NexoTheme(darkTheme = darkTheme) {
                NexoNavHost(
                    installDeepLinkController = installDeepLinkController,
                    googleDriveAuthHelper = googleDriveAuthHelper,
                    activeUserProvider = activeUserProvider,
                    libraryRepository = libraryRepository,
                    readerRepository = readerRepository,
                    readingStatsRepository = readingStatsRepository,
                    readerPreferences = readerPreferences,
                    dictionaryRepository = dictionaryRepository,
                    supabaseProgressSync = supabaseProgressSync,
                    translationRepository = translationRepository,
                    translationPreferences = translationPreferences,
                    definitionRepository = definitionRepository,
                    definitionPreferences = definitionPreferences,
                    catalogProvider = catalogProvider,
                    connectivityObserver = connectivityObserver,
                    downloadAndImportBookUseCase = downloadAndImportBookUseCase,
                    addonRegistry = addonRegistry,
                    addonAccessResolver = addonAccessResolver,
                    syncService = syncService,
                    notificationStore = notificationStore,
                    notificationToastBus = notificationToastBus,
                    notificationRepository = notificationRepository,
                    getStatisticsUseCase = getStatisticsUseCase,
                    readingGoalRepository = readingGoalRepository,
                    readingGoalPreferences = readingGoalPreferences,
                    storageRepository = storageRepository,
                    cacheRepository = cacheRepository,
                    translationHistoryRepository = translationHistoryRepository,
                    notificationPreferencesStore = notificationPreferencesStore,
                    coverStorage = coverStorage,
                    checkForUpdatesUseCase = checkForUpdatesUseCase,
                    updatePrefs = updatePrefs,
                    updateNetworkGate = updateNetworkGate,
                    updateDownloader = updateDownloader,
                    appThemeMode = appThemeMode,
                    onAppThemeModeChanged = { mode ->
                        appThemeMode = mode
                        appThemePrefs.save(mode)
                    },
                )
            }

            // A1 - cold start end boundary: one-shot first-frame report
            // (reportFullyDrawn()-style). The listener unregisters itself on
            // the first draw; async warm-up started in onCreate is excluded
            // because the window ends here at the first frame.
            val startElapsed = NexoApplication.appStartElapsedRealtime
            if (startElapsed > 0L) {
                window.decorView.viewTreeObserver.addOnPreDrawListener(
                    object : android.view.ViewTreeObserver.OnPreDrawListener {
                        private var reported = false

                        override fun onPreDraw(): Boolean {
                            window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                            if (!reported) {
                                reported = true
                                val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsed
                                com.nexo.debug.SentryMetrics.distribution(
                                    "app_cold_start",
                                    com.nexo.debug.SentryMetrics
                                        .bucketDurationMs(elapsed),
                                    mapOf(
                                        "platform" to "android",
                                        "source" to "app_shell",
                                    ),
                                )
                                runCatching { reportFullyDrawn() }
                            }
                            return true
                        }
                    },
                )
            }
        }
    }

    /**
     * Receives the Drive OAuth redirect (singleTop launch mode) after the user
     * completes/denies the Google Drive OAuth consent screen in the browser.
     * The redirect URI follows Google's reserved native-app pattern
     * `com.googleusercontent.apps.<android-client-id>:/oauth2redirect`; the scheme
     * is derived from the Android client ID (no literal in code).
     * Forwards the URI to the singleton helper, which completes the pending PKCE
     * attempt and publishes the outcome for the UI that started the flow.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Install host check FIRST (spec REQ warm-start routing order):
        // install URIs go to the controller and never reach handleDeeplinks.
        val uri = intent.data
        if (InstallDeepLinkParser.isInstallUri(uri)) {
            installDeepLinkController.onInstallUri(uri)
            return
        }
        // Warm-start email confirmation: import the session so the next launch
        // restores it (cold-start restore is the documented auth flow).
        handleAuthDeepLink(intent)
        val driveRedirectScheme = "com.googleusercontent.apps.${
            BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID.removeSuffix(".apps.googleusercontent.com")
        }"
        if (uri != null && uri.scheme == driveRedirectScheme) {
            googleDriveAuthHelper.onRedirect(uri)
        }
    }

    /**
     * Routes an incoming auth deep link (OAuth callback, password reset, email
     * confirmation) to supabase-kt.
     *
     * supabase-kt matches the intent against the single configured
     * [DeepLinkSchemes.AUTH] scheme with strict equality (`Android.kt`:
     * `if (scheme != auth.config.scheme || host != auth.config.host) return`),
     * so a canonical `nexo://auth/...` URI is normalised to that scheme before
     * the call. This is what makes the manifest's promise true — both registered
     * schemes end up in the very same handler.
     */
    private fun handleAuthDeepLink(intent: Intent) {
        val data = intent.data ?: return
        val scheme = data.scheme
        val normalised =
            if (scheme != null && scheme != DeepLinkSchemes.AUTH && scheme in DeepLinkSchemes.accepted) {
                data.buildUpon().scheme(DeepLinkSchemes.AUTH).build()
            } else {
                data
            }
        runCatching {
            SupabaseClientProvider.client.handleDeeplinks(Intent(intent).setData(normalised))
        }
    }

    /**
     * Reads the persisted last Sentry eventId + dismissed set; if there's
     * a pending crash the user hasn't acted on, launches [FeedbackActivity].
     * No-op when Sentry is uninitialized (lastEventId would be "unknown" —
     * we still prompt, since the feedback flow is local-first and only
     * submits best-effort to Sentry).
     */
    private fun maybeShowFeedbackSheet() {
        val persistence = FeedbackPersistence(this)
        val lastEventId = persistence.readLastEventId() ?: return
        if (lastEventId in persistence.readDismissed()) {
            // Already dismissed by this user; clear the last-event-id so we
            // don't keep checking on every launch.
            persistence.clearLastEventId()
            return
        }
        startActivity(
            FeedbackActivity.intent(
                context = this,
                eventId = lastEventId,
                // The process died before we could record where the user was, so
                // we claim no book: the sheet shows a neutral screen context
                // rather than inventing one (T3).
                crashContext = CrashContext.InScreen(screenRoute = null),
            ),
        )
    }

    // ── ActionMode override (debug only) ──────────────────────────────
    //
    // Android's [Activity.onActionModeStarted] is the activity-level hook
    // for the system-managed selection toolbar. In debug builds we kill any
    // ActionMode immediately so the native Copy/Share/Select All floating
    // bar never appears on top of the custom color-picker / context menu.
    //
    // For TYPE_FLOATING (the floating toolbar introduced in API 23), the
    // [View.startActionMode] variant is what creates it; the WebView
    // setCustomSelectionActionModeCallback only suppresses the regular
    // text-selection mode. Overriding onActionModeStarted at the Activity
    // level is the most reliable cross-API nuclear option.
    override fun onActionModeStarted(mode: ActionMode) {
        if (BuildConfig.DEBUG) {
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mode.type.toString()
                } else {
                    "PRIMARY"
                }
            DebugLog.warn(
                "ActionMode",
                "onActionModeStarted: title='${mode.title}', type=$type",
            )
            DebugStateHolder.recordActionModeEvent("onActionModeStarted", type)
            mode.finish()
            return
        }
        super.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        if (BuildConfig.DEBUG) {
            DebugLog.info("ActionMode", "onActionModeFinished: title='${mode.title}'")
            DebugStateHolder.recordActionModeEvent("onActionModeFinished", "—")
        }
        super.onActionModeFinished(mode)
    }
}

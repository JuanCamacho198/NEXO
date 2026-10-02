package com.nexo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.data.session.AppThemePreferences
import com.nexo.debug.CrashNotificationHelper
import com.nexo.debug.DebugLog
import com.nexo.debug.DebugPrefs
import com.nexo.debug.DebugStateHolder
import com.nexo.debug.FeedbackActivity
import com.nexo.debug.FeedbackEvent
import com.nexo.debug.FeedbackPersistence
import com.nexo.di.AppContainer
import com.nexo.domain.model.ThemeMode
import com.nexo.presentation.navigation.DeepLinkSchemes
import com.nexo.presentation.navigation.InstallDeepLinkParser
import com.nexo.presentation.navigation.NexoNavHost
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.handleDeeplinks
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var appContainer: AppContainer

    // Must be registered before onCreate (per the AndroidX ActivityResult API contract).
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* result is informational — we post only if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WU2a correction (verify W3): the cache -> canonical progress backfill
        // must be reachable without a Drive login. GoogleDriveSyncService.bootstrap
        // still runs it on auth, but that path is session-gated; a legacy device
        // that never signs in must still migrate every reading position out of
        // the retired cache column before WU2b drops it. Fire-and-forget on the
        // container's IO scope so app startup is never blocked.
        appContainer.progressBackfillRunner.schedule()

        // Addon install deep links (nexo://install?url=..., legacy
        // nextpage://install?url=...) are checked FIRST: install URIs are never
        // auth URIs, so supabase handleDeeplinks is skipped for them entirely
        // (spec REQ routing order). Invalid install links (missing/non-https
        // url) also route here so the controller can surface the https-required
        // error dialog (verify D1).
        if (InstallDeepLinkParser.isInstallUri(intent?.data)) {
            appContainer.installDeepLinkController.onInstallUri(intent?.data)
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

        // Debug-only: capture pending crash from previous run, ensure the
        // notification channel exists, and request POST_NOTIFICATIONS on 33+.
        if (BuildConfig.DEBUG && DebugPrefs.isEnabled(this)) {
            CrashNotificationHelper.ensureChannel(this)
            CrashNotificationHelper.showCrashNotificationIfAny(this)
            maybeRequestNotificationPermission()
        }

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
                    appContainer = appContainer,
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
            appContainer.installDeepLinkController.onInstallUri(uri)
            return
        }
        // Warm-start email confirmation: import the session so the next launch
        // restores it (cold-start restore is the documented auth flow).
        handleAuthDeepLink(intent)
        val driveRedirectScheme = "com.googleusercontent.apps.${
            BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID.removeSuffix(".apps.googleusercontent.com")
        }"
        if (uri != null && uri.scheme == driveRedirectScheme) {
            appContainer.googleDriveAuthHelper.onRedirect(uri)
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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
                book =
                    FeedbackEvent.BookMeta(
                        bookId = "",
                        title = null,
                        chapterLabel = null,
                        chapterIndex = null,
                        page = null,
                    ),
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

package com.nexo

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.debug.CrashLogStore
import com.nexo.debug.DebugLog
import com.nexo.debug.FeedbackPersistence
import com.nexo.debug.SentryInitGuard
import com.nexo.debug.SentryMetrics
import com.nexo.debug.SentryPiiScrubber
import com.nexo.debug.SentryPrivacyPrefs
import com.nexo.presentation.theme.CoilModule
import dagger.hilt.android.HiltAndroidApp
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Application entry point.
 *
 * - Implements [SingletonImageLoader.Factory] so Coil's singleton uses the tuned
 *   [CoilModule.imageLoader] (15s connect, 30s read, retry-on-connection-failure,
 *   25% memory cache, 64MB disk cache) for every `AsyncImage` call.
 * - Installs an [Thread.UncaughtExceptionHandler] (always active) that
 *   captures the last crash (timestamp, thread, message, stack trace)
 *   to `SharedPreferences` (for notification path) and writes a crash
 *   file with a log snapshot to `cacheDir/crashes/`.
 * - Chains to the previous default handler so the OS still gets the
 *   crash report (and the process still dies as expected).
 *
 * SDD android-tooling-hygiene WS2a slice 4: Hilt root. App-scoped bindings
 * live in `com.nexo.di.HiltFoundationModule` (slice 4) and
 * `com.nexo.di.HiltSingletonsModule` (slice 5: sync + deep-link
 * singletons); the manual [com.nexo.di.AppContainer]
 * still constructs entry-point graphs and delegates binding construction to the
 * shared factories until slices 5-6 migrate consumers.
 */
@HiltAndroidApp
class NexoApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    companion object {
        private const val TAG = "NexoApplication"
        const val PREFS_NAME = "nexo_debug_crash"
        const val KEY_LAST_CRASH = "last_crash"

        /** A1 - cold start origin: first line of Application.onCreate. */
        @Volatile
        var appStartElapsedRealtime: Long = 0L
            private set

        // Sentry capture rates (named constants to keep detekt's MagicNumber rule quiet).
        // - TRACES_SAMPLE_RATE: 10% of transactions are sampled for performance traces.
        // - ON_ERROR_REPLAY_RATE: 10% of errors get a 30s session replay attached
        //   (only when DSN is configured; replay is OFF for sessions, ON only on error).
        private const val TRACES_SAMPLE_RATE = 0.1
        private const val ON_ERROR_REPLAY_RATE = 0.1
    }

    private lateinit var debugLogScope: CoroutineScope
    private lateinit var crashLogStore: CrashLogStore
    private lateinit var crashDir: File
    private lateinit var feedbackStore: FeedbackPersistence

    /**
     * WorkManager on-demand configuration (S6). The default
     * `androidx.startup` WorkManagerInitializer is removed in
     * `AndroidManifest.xml`, so this provider is the only configuration
     * WorkManager observes — it supplies the Hilt-backed worker factory used to
     * construct `@HiltWorker` classes (OutboxDrainWorker).
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val supabaseWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appStartElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        crashDir = File(cacheDir, "crashes").also { it.mkdirs() }
        val logDir = File(cacheDir, "logs").also { it.mkdirs() }
        debugLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        crashLogStore = CrashLogStore(logDir)
        crashLogStore.cleanup(crashDir)
        feedbackStore = FeedbackPersistence(this)
        DebugLog.init(debugLogScope, crashLogStore)

        // Sentry must be initialized BEFORE installCrashHandler so the chained
        // UncaughtExceptionHandler can capture to Sentry before the process dies.
        // SentryAndroid.init is a no-op when DSN is empty (BuildConfig.SENTRY_DSN
        // comes from local.properties which is gitignored; see app/build.gradle.kts).
        // We deliberately do NOT attach screenshots or view hierarchy:
        // this is a reader app and we must not leak book content to Sentry.
        // FIX 4 (NEXTPAGE-ANDROID-3): never initialise the real SDK on a JVM test
        // classpath (JUnit/Robolectric instantiate this Application) — synthetic
        // test failures must not egress to the production project.
        if (SentryInitGuard.shouldInitialize()) {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN.takeIf { it.isNotEmpty() }
                // Spec C1 — cross-platform release `<prefix>@<version>+<sha12>`, where
                // the prefix is BuildConfig.SENTRY_RELEASE_PREFIX (`nexo-android`,
                // single-sourced from `sentryProject` in app/build.gradle.kts and shared
                // with the Sentry Gradle plugin's projectName, so the release string
                // cannot drift from the uploaded project).
                // GIT_SHA is emitted by `build.gradle.kts` from `git rev-parse --short=12 HEAD`
                // (or `unknown` fallback) and is identical to the TS web build for the same
                // commit. See `sdd/sentry-observability-v2/design` data-flow note C.
                options.release = "${BuildConfig.SENTRY_RELEASE_PREFIX}@${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_SHA}"
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.tracesSampleRate = TRACES_SAMPLE_RATE
                // Spec C2 — explicit session tracking (web sets autoSessionTracking=true;
                // Android must enable it explicitly to prove sessions flow in review).
                options.isEnableAutoSessionTracking = true
                // No screenshots / view hierarchy: reader app, must not leak book content.
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                // Replay only on error, with strict PII masking defaults from the SDK.
                options.sessionReplay.sessionSampleRate = 0.0
                options.sessionReplay.onErrorSampleRate = ON_ERROR_REPLAY_RATE
                // PII redaction layer (rule-5 gate) + PP-3 opt-out, THEN the
                // DEBUG-level drop: scrubber runs first, level filter second.
                options.beforeSend =
                    SentryOptions.BeforeSendCallback { event, _ ->
                        if (!SentryPrivacyPrefs.isEnabled(this@NexoApplication)) {
                            return@BeforeSendCallback null
                        }
                        val scrubbed = SentryPiiScrubber.scrubEvent(event)
                        if (scrubbed.level == SentryLevel.DEBUG) null else scrubbed
                    }
                options.beforeBreadcrumb =
                    SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                        if (!SentryPrivacyPrefs.isEnabled(this@NexoApplication)) {
                            return@BeforeBreadcrumbCallback null
                        }
                        SentryPiiScrubber.filterBreadcrumb(crumb)
                    }
            }
        }

        // PP-3 opt-out must also cover metrics: Sentry metric envelope items bypass
        // beforeSend/beforeBreadcrumb, so the veto above never sees them. Send the
        // real supplier to the emitter so the settings switch stops metric egress
        // immediately, mid-session.
        SentryMetrics.install { SentryPrivacyPrefs.isEnabled(this@NexoApplication) }

        installCrashHandler()

        // Warm the Supabase client on a background thread so the first Activity
        // frame never pays the ~2s client-construction cost on the main thread
        // (measured via logcat: AppContainer fully initialized in ~1950ms).
        // The client is created lazily on first use if this warm-up races.
        supabaseWarmupScope.launch {
            runCatching { SupabaseClientProvider.client }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = CoilModule.imageLoader(context)

    /**
     * Public accessor for the feedback persistence — used by
     * [CrashDetailActivity] (in-memory trigger) and [MainActivity]
     * (next-launch prompt) to read/write the queue + dismissed set.
     */
    val feedbackPersistence: FeedbackPersistence
        get() = feedbackStore

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Capture to Sentry FIRST so the event has a chance to flush before
                // the process dies. Sentry.captureException returns the SentryId
                // which we persist as `feedback_last_event_id` so the next-launch
                // feedback sheet can link the report back to the crash (spec D3).
                val sentryId = Sentry.captureException(throwable)
                runCatching {
                    feedbackStore.recordLastEventId(sentryryIdString(sentryId))
                }

                val stackTrace = Log.getStackTraceString(throwable)
                val crashJson =
                    JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("threadName", thread.name)
                        put("message", throwable.message ?: throwable::class.java.simpleName)
                        put("stackTrace", stackTrace)
                    }
                // Save to SharedPreferences (existing — for notification)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, crashJson.toString())
                    .apply()

                // Save crash + log snapshot to file
                runCatching {
                    val logs = crashLogStore.snapshot()
                    val crashFile = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                    crashFile.bufferedWriter().use { out ->
                        out.write("Timestamp: ${crashJson.optLong("timestamp")}\n")
                        out.write("Thread: ${crashJson.optString("threadName")}\n")
                        out.write("Message: ${crashJson.optString("message")}\n")
                        out.write("--- Stack Trace ---\n")
                        out.write(crashJson.optString("stackTrace"))
                        if (logs.isNotEmpty()) {
                            out.write("\n--- Logs ---\n")
                            logs.forEach { out.write("$it\n") }
                        }
                    }
                }

                DebugLog.error(TAG, "Captured crash on thread ${thread.name}: ${throwable.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to capture crash", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
        DebugLog.info("CrashHandler", "Installed (Sentry + local)")
    }

    /**
     * Extract the canonical string id from a [io.sentry.protocol.SentryId].
     * Falls back to `"unknown"` when Sentry is uninitialized (returns
     * `SentryId.EMPTY_ID` whose string is `00000000-…`).
     */
    private fun sentryryIdString(id: io.sentry.protocol.SentryId): String =
        runCatching {
            id.toString()
        }.getOrDefault("unknown")
}

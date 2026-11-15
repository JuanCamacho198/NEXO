plugins {
    id("nexo.android.library")
}

// SDD M3: cross-cutting debug/telemetry instrumentation extracted from :app.
//
// These files sit on the observability path of MANY modules (:core:data, and the
// app's presentation/ui/di layers alike), which is why they own a module of their
// own instead of living inside either consumer. The package stays com.nexo.debug
// and every call site stays byte-identical: only the Gradle module boundary moves.
android {
    namespace = "com.nexo.debug"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // SentryMetrics / SentryPiiScrubber / DebugDual / DebugLog egress through the
    // Sentry Android SDK directly (captureMessage, breadcrumbs, metrics).
    api(libs.sentry.android)
    // Flow-based event ring buffers (DebugLog, DebugStateHolder).
    api(libs.kotlinx.coroutines.android)
}

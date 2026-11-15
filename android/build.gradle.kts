plugins {
    alias(libs.plugins.android.application) apply false
    // SDD android-stack-modernization S9: `com.android.test` for the macrobenchmark
    // module. Declared here (apply false) so its version resolves ONCE — the
    // `com.android.application` and `com.android.test` markers share a single AGP
    // implementation artifact, and resolving the test marker against an
    // already-present classpath entry fails version-compatibility checking.
    alias(libs.plugins.android.test) apply false
    // SDD android-stack-modernization S11: Baseline Profile Gradle plugin. Applied as a
    // CONSUMER in app/build.gradle.kts and as a PRODUCER in benchmark/build.gradle.kts;
    // declared here (apply false) so its version resolves ONCE.
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Pure-JVM Kotlin for the extracted :core:model / :core:domain modules.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false

    // Sentry Android Gradle Plugin (SAGP). Resolved to the latest stable
    // version on Maven Central at the time of PR 3 (sentry-cross-platform):
    // 6.21.0 (released 2026-09-03). The plugin auto-installs a compatible
    // io.sentry:sentry-android runtime via Gradle dependency resolution;
    // we additionally pin sentry-android:8.54.0 in app/build.gradle.kts
    // for explicit version control.
    alias(libs.plugins.sentry.gradle) apply false

    // SDD android-tooling-hygiene WS2a slice 4: Hilt (declared in the catalog;
    // applied in app/build.gradle.kts).
    alias(libs.plugins.hilt) apply false

    // SDD android-tooling-hygiene WS5 slice 7: Spotless (declared in the
    // catalog; applied in app/build.gradle.kts).
    alias(libs.plugins.spotless) apply false

    // SDD android-tooling-hygiene WS4 slice 9: Kover report-only coverage
    // (declared in the catalog; applied in app/build.gradle.kts).
    alias(libs.plugins.kover) apply false
}

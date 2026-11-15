plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// SDD M7: reader feature module extracted from :app. It owns the Readium
// bridge (locator/decoration/preference mappers, the fragment host and the
// selection bridges), the reader state holders and managers under
// com.nexo.presentation.viewmodel.reader, the reader settings sections and the
// screen-level hosts (content host, overlays host, effects, dismissal wiring).
//
// Package policy: the Kotlin packages stay
// `com.nexo.presentation.screen.readium`,
// `com.nexo.presentation.screen.reader`,
// `com.nexo.presentation.viewmodel.reader` and
// `com.nexo.ui.components.molecules.reader`
// so no import in :app changes and the move stays purely structural.
//
// Dependency rule: :feature:reader depends only on :core:* modules (plus the
// already-extracted :feature:highlights, which owns the highlights sheet the
// reader overlay host opens). It must never depend on :app — that would be a
// cycle.
//
// What stays in :app, and why:
//  - `presentation/viewmodel/ReaderViewModel.kt` reads `com.nexo.R`, which is
//    :app-only, and is a Hilt-free but app-scoped factory wired by
//    ViewModelProviders. Moving it would require shipping :app's R, which is
//    the cycle this module must avoid.
//  - `presentation/viewmodel/CfiMigrator.kt` is technically clean (:core:data
//    only) but is consumed by library/book-detail code outside the reader, so
//    moving it would widen this module's public surface for no gain.
//  - `presentation/screen/ReaderScreen.kt` binds ReaderViewModel and
//    `com.nexo.BuildConfig`; it stays as the ViewModel-bound entry point.
//  - `Readium{Pdf,}ReaderContent.kt` take a ReaderViewModel parameter, so they
//    stay next to the ViewModel as thin wrappers.
//  - `BookmarkRibbonOverlay.kt` resolves `com.nexo.R`.
//  - `HighlightsScreenShim.kt` is a deliberate :app-side FQN shim over
//    :feature:highlights helpers, kept where its consumers are.
//
// String policy: this module declares its own string resources. Keys moved here
// only when every reference lived inside the reader sources; keys that :app or
// another feature also resolve stayed in app/src/main/res.
//
// BuildConfig policy: nothing here reads BuildConfig today, so the feature is
// left off. Turn it on (never `com.nexo.BuildConfig`) if that changes.
android {
    namespace = "com.nexo.feature.reader"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Design system: theme, icons, atoms and molecules the reader renders.
    // :core:ui exposes the Compose BOM, material3 and the Compose UI bundle via api().
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:domain"))

    // The reader loads publications through the repositories and maps them to
    // domain locators. :core:data exposes :core:domain and :core:model via api().
    api(project(":core:data"))

    // ReaderScreenOverlaysHost opens the highlights sheet, which lives in the
    // already-extracted highlights feature.
    api(project(":feature:highlights"))

    // DebugLog traces load/selection failures.
    implementation(project(":core:debug"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // ReadiumFragmentFactorySetup installs a FragmentFactory on the
    // FragmentActivity that hosts the Readium navigator fragments.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Readium Kotlin Toolkit (EPUB + PDF rendering). The bridges compile the
    // navigator, streamer and PDF adapter types directly.
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium) {
        exclude(group = "androidx.appcompat")
    }

    testImplementation(libs.testing.junit4)
    testImplementation(libs.testing.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // The moved Readium bridge tests are Robolectric tests: the locator and
    // overlay mappers touch android.graphics and the fragment host.
    testImplementation(libs.testing.robolectric)
    // SearchStateHolder parses PDF search payloads with org.json, which the
    // plain JVM unit-test runtime stubs out unless this real implementation is
    // on the classpath.
    testImplementation(libs.testing.json)
}

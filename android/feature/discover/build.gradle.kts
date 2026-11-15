plugins {
    id("nexo.android.library.compose")
}

// SDD M5: discover feature module extracted from :app. It owns the search/rails
// shell, the "Ver todo" section list, the detail sheet, the read-access and
// addon-read sheets, the feedback states and the results grid.
//
// Package policy: the Kotlin package stays `com.nexo.presentation.feature.discover`
// so no import in :app changes and the move stays purely structural.
//
// Dependency rule: :feature:discover depends only on :core:* modules. It must
// never depend on :app (that would be a cycle).
//
// String policy: this module declares its own string resources. A key moved here
// because every reference to it lived inside this module; keys still resolved by
// :app (or by another :app screen) stay in app/src/main/res.
android {
    namespace = "com.nexo.feature.discover"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Design system: theme, icons and the atoms/molecules the screens render.
    // :core:ui exposes the Compose BOM, material3 and the Compose UI bundle via api().
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:domain"))

    // The discover ViewModels page the catalog through CatalogProvider and read
    // CatalogSources/addonSource. :core:data exposes :core:domain and :core:model
    // via api().
    api(project(":core:data"))

    // SentryMetrics counts the cover-failure funnel (DiscoverBookCover) and
    // DebugLog traces section paging.
    implementation(project(":core:debug"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // The results grid and the detail head load covers through Coil; the
    // version comes from the catalogue rather than :core:ui's api() export
    // because this module compiles AsyncImage itself.
    implementation(libs.coil.compose)

        // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
testImplementation(libs.testing.junit4)
    testImplementation(libs.testing.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    id("nexo.android.library.compose")
}

// SDD M6: highlights feature module extracted from :app. This slice owns the
// presentational substrate: the card composables, the reader detail sheet, the
// UI-state builder + filter logic, and the pure colour/location utils.
//
// Package policy: the Kotlin package stays `com.nexo.presentation.feature.highlights`
// so no import in :app changes and the move stays purely structural.
//
// Dependency rule: :feature:highlights depends only on :core:* modules. It must
// never depend on :app (that would be a cycle).
//
// Scope note: the screen shell (HighlightsScreen/Header/List/Filter/Dialogs) and
// the ViewModel cluster are NOT here yet. They need `com.nexo.presentation.UiEvent`
// and seven design-system composables that still live in :app. See the module
// report; those land in a follow-up slice once :core:ui is completed.
android {
    namespace = "com.nexo.feature.highlights"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Design system: theme, icons and the atoms/molecules this slice renders.
    // :core:ui exposes the Compose BOM, material3 and the Compose UI bundle via api().
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:domain"))

    implementation(libs.androidx.core.ktx)

        // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
testImplementation(libs.testing.junit4)
    testImplementation(libs.testing.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

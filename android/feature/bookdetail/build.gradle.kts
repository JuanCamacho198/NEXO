plugins {
    id("nexo.android.library.compose")
}

// SDD M8: book-detail feature module extracted from :app. It owns the detail
// screen shell (hero, metadata grid, chips, synopsis, reading progress), its
// preview fixtures, and the ViewModel + UiState cluster that drives them.
//
// Package policy: the Kotlin packages stay
// `com.nexo.presentation.feature.bookdetail` and
// `com.nexo.presentation.viewmodel` so no import in :app changes and the move
// stays purely structural.
//
// Dependency rule: :feature:bookdetail depends only on :core:* modules. It must
// never depend on :app (that would be a cycle).
//
// Why `editmetadata` is NOT in this module: the two directories share no
// reference, no state type and no screen shell — each owns its own
// PreviewFixtures with a different book, and they share 2 of their string keys.
// They are two independent destinations that happen to be declared in the same
// NavGraph, not one domain. See feature/editmetadata/build.gradle.kts absence in
// the module report.
//
// String policy: this module declares its own string resources. The 24
// `book_detail_*` keys moved here because every reference lived inside these
// sources. `format_percent`, `library_menu_edit_metadata`,
// `library_share_chooser_title` and `reader_cancel` stay in :app, which still
// resolves them.
//
// BuildConfig policy: nothing here reads BuildConfig, so the feature is left
// off. Turn it on (never `com.nexo.BuildConfig`) if that changes.
android {
    namespace = "com.nexo.feature.bookdetail"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Design system: theme, icons and the atoms/molecules the detail sections render.
    // :core:ui exposes the Compose BOM, material3 and the Compose UI bundle via api().
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:domain"))

    // BookDetailViewModel reads Book/ReadingProgress through LibraryRepository and
    // ActiveUserProvider. :core:data exposes :core:domain and :core:model via api().
    api(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
    testImplementation(libs.testing.junit4)
    testImplementation(libs.testing.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

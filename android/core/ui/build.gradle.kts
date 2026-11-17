plugins {
    id("nexo.android.library.compose")
}

// SDD M4: shared design system extracted from :app so feature modules can own
// their composables without depending on :app (which would be a cycle).
//
// PARTIAL by design. This module owns the theme, the icon set and the subset of
// atoms/molecules that :feature:settings needs. The rest of `ui/components/**`
// stays in :app and will follow in later slices once its `R.string` call sites
// are parameterised. Because packages do NOT change, a file here and a file at
// the same package path in :app coexist: :app depends on both.
//
// String policy: this module declares NO string resources. Components that used
// to resolve `R.string.*` internally take the resolved text as a parameter, and
// the caller (`:app` or a feature module) supplies `stringResource(...)`.
android {
    namespace = "com.nexo.ui"
}

dependencies {
    // Typography loads the Lato families through `R.font.*`; those ttf assets
    // live in this module's own res/font.
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.bundles.compose.ui)
    api(libs.compose.material.icons.extended)

    // CoilModule (image loader) uses Coil 3 + the OkHttp network engine.
    api(libs.coil)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)

    // NexoSnackbar awaits the snackbar animation on the caller's coroutine.
    api(libs.kotlinx.coroutines.android)

    // HighlightPaletteSection renders domain HighlightColor values.
    api(project(":core:domain"))

    // SafeResourcePainter logs a missing drawable through DebugDual.
    implementation(project(":core:debug"))

    // WindowSizeClass breakpoints: `api` so the reader, library and discover
    // surfaces can branch on the same object without each declaring it.
    //
    // Does NOT use `androidx.compose.material3.windowsizeclass.calculateWindowSizeClass`:
    // material3 1.4.0 (the version this repo's BOM resolves) removed it. The
    // replacement here is `androidx.window.core.layout.WindowSizeClass`, which is
    // what material3 1.5 moved to anyway, so the next bump does not churn this.
    // This artifact is already on the classpath transitively at this exact version
    // via activity-compose; the declaration only makes the direct use explicit.
    api(libs.androidx.window)

    // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
    testImplementation(libs.testing.junit4)
}

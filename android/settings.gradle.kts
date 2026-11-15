pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NexoAndroid"
include(":app")
// SDD modularization M1/M2: pure-JVM core modules extracted from :app. Packages
// and file contents are unchanged; only the Gradle module boundary moved.
include(":core:model")
include(":core:domain")
// SDD M3: cross-cutting debug/telemetry instrumentation (com.nexo.debug). It is an
// Android library because it egresses through the Sentry Android SDK and reads
// android.util.Log. Both :app and :core:data depend on it; :core:domain does not.
include(":core:debug")
// SDD M3: data layer extracted from :app (com.nexo.data.*). An Android library
// because it uses Context, Room, OkHttp/Ktor, Supabase and Android file APIs.
// Direction: core:data -> core:domain -> core:model; core:data -> core:debug.
include(":core:data")
include(":core:ui")
// SDD M4: first feature module. Depends on :core:* only and keeps the
// com.nexo.presentation.screen.settings package so :app imports stay unchanged.
include(":feature:settings")
// SDD M5: second feature module. Same dependency rule as :feature:settings —
// :core:* only, never :app — and the same package policy, so :app imports of
// com.nexo.presentation.feature.discover keep resolving.
include(":feature:discover")
// SDD M6: third feature module. Same rules as :feature:settings and :feature:discover —
// :core:* only, never :app — and the same package policy, so :app imports of
// com.nexo.presentation.feature.highlights keep resolving.
include(":feature:highlights")
// SDD android-stack-modernization S9: macrobenchmark test module. It is a
// self-instrumenting `com.android.test` module targeting `:app` (see
// benchmark/build.gradle.kts) and is NOT part of the normal build graph — every
// variant except `benchmark` is disabled.
include(":benchmark")
// SDD M6: third feature module. Same dependency rule as :feature:settings and
// :feature:discover -- :core:* only, never :app -- and the same package policy,
// so :app imports of com.nexo.presentation.feature.auth keep resolving. The
// three ViewModel-bound AuthScreen/RegisterScreen/ForgotScreen entry points stay
// in :app because AuthViewModel is a :app Hilt type.
include(":feature:auth")

// SDD M7: reader feature module. Same rules as the other feature modules --
// :core:* only, never :app -- and the same package policy, so :app imports of
// com.nexo.presentation.screen.readium, com.nexo.presentation.screen.reader and
// com.nexo.presentation.viewmodel.reader keep resolving.
//
// This is the product core: the Readium bridges, the selection/tap-zone
// plumbing, the reader state holders and managers, the reader settings
// sections, and the screen-level hosts. ReaderViewModel stays in :app (it needs
// com.nexo.R) and so do the screen entry points that bind it.
include(":feature:reader")

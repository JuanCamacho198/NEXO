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
// SDD android-stack-modernization S9: macrobenchmark test module. It is a
// self-instrumenting `com.android.test` module targeting `:app` (see
// benchmark/build.gradle.kts) and is NOT part of the normal build graph — every
// variant except `benchmark` is disabled.
include(":benchmark")

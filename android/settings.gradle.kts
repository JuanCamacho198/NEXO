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
// SDD android-stack-modernization S9: macrobenchmark test module. It is a
// self-instrumenting `com.android.test` module targeting `:app` (see
// benchmark/build.gradle.kts) and is NOT part of the normal build graph — every
// variant except `benchmark` is disabled.
include(":benchmark")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Shared baseline for the Android library modules extracted from :app.
//
// Deliberately NOT applied here:
//  - `namespace` — it is per-module.
//  - `buildFeatures.buildConfig` — opt-in per module (only four need it).
//  - `dependencies` — each module declares its own.
//  - `testOptions` — only some modules enable Android resources in unit tests.
android {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

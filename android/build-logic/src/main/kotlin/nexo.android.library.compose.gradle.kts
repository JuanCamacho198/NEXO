plugins {
    id("nexo.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Adds the Compose compiler plugin and the Compose build feature on top of
// `nexo.android.library`. Compose dependencies stay per module: :core:ui exports
// the BOM/material3 bundle via api(), the features consume them.
android {
    buildFeatures {
        compose = true
    }
}

import java.util.Properties

plugins {
    id("nexo.android.library.compose")
}

// SDD M6: auth feature module extracted from :app. It owns the entry-flow
// screens (sign-in, register, password reset), the Google One Tap button and
// the Nexo brand lockup.
//
// Package policy: the Kotlin package stays `com.nexo.presentation.feature.auth`
// so no import in :app changes and the move stays purely structural.
//
// Dependency rule: :feature:auth depends only on :core:* modules. It must
// never depend on :app (that would be a cycle). The three `AuthScreen` /
// `RegisterScreen` / `ForgotScreen` composables that take an AuthViewModel stay
// in :app, because AuthViewModel is a :app Hilt type consumed by MainActivity,
// DebugPanel, AuthNavGraph, SettingsNavGraph and ViewModelProviders.
//
// String policy: this module declares its own string resources. A key moved
// here because every reference to it lived inside this module; `app_name`,
// `nav_back` and `auth_snackbar_reset_email_sent` stay in :app because
// non-auth code (or AuthViewModel) resolves them.
//
// BuildConfig policy: GoogleSignInButton needs GOOGLE_OAUTH_CLIENT_ID and
// AuthDevBypass needs DEBUG. This module re-declares the client id from
// local.properties (`google.oauth.client.id`) instead of reading the :app
// BuildConfig, which it cannot see.
// local.properties is gitignored and absent on CI. A blank client id keeps the
// build green: GoogleSignInButton resolves the disabled reason at runtime and
// shows the "not configured" copy instead of failing to compile.
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val googleOAuthClientId =
    (localProperties.getProperty("google.oauth.client.id") ?: "").escapeForBuildConfig()

android {
    namespace = "com.nexo.feature.auth"

    defaultConfig {
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleOAuthClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Design system: theme, icons, the atoms the screens render, AuthTags and
    // safePainterResource. :core:ui exposes the Compose BOM, material3 and the
    // Compose UI bundle via api().
    api(project(":core:ui"))

    // AuthUiState / AuthFailureKind moved to com.nexo.domain.auth.
    api(project(":core:domain"))
    api(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Google One Tap. GetGoogleIdOption / GoogleIdTokenCredential are compiled
    // directly by GoogleSignInButton.
    implementation(libs.google.id)

        // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
testImplementation(libs.testing.junit4)
}

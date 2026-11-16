import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    id("nexo.android.library.compose")
}

// SDD M4: feature module extracted from :app. It owns the settings screens, the
// performance cards, the addon management screen and the About surface.
//
// Package policy: the Kotlin package stays `com.nexo.presentation.screen.settings`
// so no import in :app changes and the move stays purely structural.
//
// Dependency rule: :feature:settings depends only on :core:* modules. It must
// never depend on :app (that would be a cycle).
//
// String policy: this module declares its own string resources. A key moved here
// because every reference to it lived inside this module; keys still resolved by
// :app stay in app/src/main/res.
android {
    namespace = "com.nexo.feature.settings"

    defaultConfig {
        // The About screen prints the app version and the git revision, so this
        // module carries its own BuildConfig instead of reaching into :app's.
        // The version mirrors `appVersionName` in :app; release-please keeps the
        // marker comment on both lines.
        val featureVersionName = "0.6.1" // x-release-please-version
        buildConfigField("String", "VERSION_NAME", "\"$featureVersionName\"")
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        // SettingsDataStorageScreen shows the OAuth client id. A library module cannot
        // read :app's BuildConfig, so the value is re-declared here from the same
        // local.properties source.
        val oauthClientId =
            Properties().let { props ->
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { stream -> props.load(stream) }
                props.getProperty("GOOGLE_OAUTH_ANDROID_CLIENT_ID").orEmpty()
            }
        buildConfigField("String", "GOOGLE_OAUTH_ANDROID_CLIENT_ID", "\"$oauthClientId\"")
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the debug-panel row in the settings list.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/** Short git revision, or "unknown" outside a work tree. */
fun gitSha(): String =
    try {
        val out = ByteArrayOutputStream()
        val result =
            providers
                .exec {
                    commandLine("git", "rev-parse", "--short=12", "HEAD")
                    workingDir = rootProject.projectDir
                    standardOutput = out
                }.result
                .get()
        if (result.exitValue != 0) {
            "unknown"
        } else {
            out
                .toString(Charsets.UTF_8)
                .trim()
                .take(12)
                .ifEmpty { "unknown" }
        }
    } catch (_: Exception) {
        "unknown"
    }

dependencies {
    // Design system: theme, icons and the atoms/molecules the screens render.
    // :core:ui exposes the Compose BOM, material3 and the Compose UI bundle via api().
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:domain"))

    // Screens read preferences and sync state directly (AppLanguagePreferences,
    // NotificationPreferencesStore, DefinitionPreferences, DriveAuthHelper,
    // AddonRegistry). :core:data exposes :core:domain and :core:model via api().
    api(project(":core:data"))

    // BuildConfig.DEBUG gates the debug-panel row (DebugPrefs.isEnabled).
    implementation(project(":core:debug"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Settings screens resolve their ViewModels with hiltViewModel().
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
    testImplementation(libs.testing.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

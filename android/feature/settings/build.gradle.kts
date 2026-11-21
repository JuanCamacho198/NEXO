import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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

// --- Third-party licence inventory ------------------------------------------
//
// The About screen's "Licenses" row needs the attribution notices of everything
// the APK actually ships. Maintaining that list by hand rots; resolving it from
// Gradle cannot. This block resolves the RUNTIME classpath of :app (the
// distributable APK), keeps only third-party entries, and writes `licenses.txt`
// into a generated asset dir merged into this module's assets, so LicensesScreen
// reads it like any bundled file.
//
// The task type lives in build-logic (`GenerateLicensesAssetTask`), not here: a
// `doLast` closure or a script-local task class cannot be serialized by the
// configuration cache this repository enables.
//
// Regeneration (runs automatically as a dependency of :app:assembleDebug):
//   ./gradlew.bat --no-daemon -Dorg.gradle.vfs.watch=false :feature:settings:generateLicensesAsset
//
// Why the filter is shaped this way: the app's runtime classpath already omits
// testImplementation / androidTestImplementation / ksp artifacts. The deny lists
// below are belt-and-braces for anything transitive (junit, robolectric, mockk,
// turbine, konsist, espresso, androidx.test) so a distributed notice never
// credits a library that is not in the APK.

/** Groups that never ship inside the APK even if resolution drags them in. */
val licensingExcludedGroups =
    setOf(
        "junit",
        "org.junit",
        "org.junit.jupiter",
        "org.junit.platform",
        "org.robolectric",
        "io.mockk",
        "app.cash.turbine",
        "com.lemonappdev",
        "androidx.test",
        "androidx.test.espresso",
        "androidx.test.ext",
        "com.android.support.test",
    )

/**
 * Exact coordinates to drop even though their group is otherwise shipped.
 *
 * `org.jetbrains.kotlinx` is deliberately NOT a denied group: kotlinx-coroutines
 * and kotlinx-serialization DO ship in the APK and need their notice. Only their
 * test artifacts are denied, matched by suffix below.
 */
val licensingExcludedCoordinates =
    setOf(
        "org.jetbrains.kotlinx:kotlinx-coroutines-test",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm",
    )

/** Artifact-name suffixes that mark a test-only artifact inside a shipping group. */
val licensingExcludedNameSuffixes = listOf("-test", "-test-jvm", "-test-common")

/** The generated asset lands here and is registered as a source dir below. */
val generatedLicensesDir = layout.buildDirectory.dir("generated/licensesAssets")

/**
 * The configuration AGP exposes for the shipped APK. An application module has
 * no plain `runtimeClasspath`; the classpath is per-buildType
 * (`debugRuntimeClasspath`, `releaseRuntimeClasspath`, ...). Release wins so the
 * inventory describes what is actually distributed.
 */
fun appRuntimeConfiguration(project: Project): Configuration {
    val configs = project.project(":app").configurations
    val name =
        configs.names.firstOrNull { it == "releaseRuntimeClasspath" }
            ?: configs.names.firstOrNull { it == "debugRuntimeClasspath" }
            ?: error(
                "':app' exposes neither releaseRuntimeClasspath nor debugRuntimeClasspath. " +
                    "The licence inventory must be resolved from the shipped APK's runtime classpath.",
            )
    return configs.getByName(name)
}

val generateLicensesAsset by
    tasks.registering(GenerateLicensesAssetTask::class) {
        group = "reporting"
        description = "Writes the third-party licence inventory of the :app runtime classpath as a bundled asset."
        // Resolved lazily so the dependency on :app's configuration is wired
        // without forcing :app to configure during this module's configuration.
        coordinates.set(
            provider {
                val result = appRuntimeConfiguration(project).incoming.resolutionResult
                result.allComponents
                    .mapNotNull { component ->
                        (component.id as? ModuleComponentIdentifier)?.let { id ->
                            "${id.group}:${id.module}:${id.version}"
                        }
                    }.filter { coordinate ->
                        val group = coordinate.substringBefore(':')
                        val name = coordinate.removePrefix("$group:").substringBeforeLast(':')
                        val groupName = "$group:$name"
                        !group.startsWith("com.nexo") &&
                            (group in licensingExcludedGroups).not() &&
                            (groupName in licensingExcludedCoordinates).not() &&
                            licensingExcludedNameSuffixes.none { name.endsWith(it) }
                    }
            },
        )
        header.set(
            "# NEXO third-party licence inventory\n" +
                "# Generated by :feature:settings:generateLicensesAsset from the\n" +
                "# :app runtime classpath. Do not edit by hand.",
        )
        // Where Gradle keeps the POM it read to resolve each module. The exact
        // file name depends on the module, so the whole `files-2.1` tree is
        // matched and PomIndex keeps only the POMs, keyed by their cache shape.
        // A file set (not a path string) keeps the task up to date when a
        // dependency is added or its POM changes.
        pomFiles.from(
            File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".pom") }
                .toList(),
        )
        outputDirectory.set(generatedLicensesDir)
    }

// Merge the generated dir into the debug + release assets without detaching this
// module's own `src/main/assets`, and make every asset-merge task depend on the
// generator so a build never packages a stale or missing inventory.
android {
    sourceSets.named("debug") { assets.srcDir(generatedLicensesDir) }
    sourceSets.named("release") { assets.srcDir(generatedLicensesDir) }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateLicensesAsset)
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

    // Compose UI assertions for the About rows. Robolectric runs the Compose
    // host on the JVM, so the row's click/role wiring is provable in
    // `testDebugUnitTest` instead of needing a device. The manifest artifact is
    // what createComposeRule launches; without it the rule cannot resolve
    // its host activity and every UI test dies before asserting anything.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.testing.robolectric)
}

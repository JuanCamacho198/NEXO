import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    // Same formatting gate as `:app` (Spotless targets **/*.kt + **/*.kts), so the
    // root `spotlessApply` / `spotlessCheck` covers this module's Kotlin too.
    alias(libs.plugins.spotless)
    // SDD android-stack-modernization S11: Baseline Profile PRODUCER plugin. Turns this
    // module into the device-side generator that `:app:generateReleaseBaselineProfile`
    // drives (BaselineProfileGenerator.kt lives beside the S9 journeys here).
    alias(libs.plugins.androidx.baselineprofile)
}

// Spotless is configured per module (mirrors :app) — the plugin alone defines no
// targets, and the benchmark sources must not escape the format gate.
spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        ktlint()
    }
}

android {
    namespace = "com.nexo.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        // The macrobenchmark library drives the journeys; `AndroidBenchmarkRunner`
        // (strict benchmark mode) is deliberately NOT referenced because
        // benchmark-macro-junit4 does not ship `benchmark-junit4`. Every
        // androidx.test coordinate resolves transitively from the macro artifact.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildTypes {
        // Only this build type is ever enabled (see beforeVariants below). AGP pairs
        // it with `:app:benchmark` by matching build-type name across targetProjectPath.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Self-instrumenting: the benchmark module supplies its own instrumentation
    // (testInstrumentationRunner above) instead of instrumenting another module's
    // test APK — required for a macrobenchmark module.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// Variants this module serves. `benchmark` is the S9 macrobenchmark variant paired with
// `:app:benchmark`; `nonMinifiedRelease` and `benchmarkRelease` are the synthetic build
// types the `androidx.baselineprofile` PRODUCER plugin creates (S11) so this module has a
// variant for every `:app` variant the profile generation instruments.
private val servedBuildTypes = setOf("benchmark", "nonMinifiedRelease", "benchmarkRelease")

// Nothing but the variants above is built; the module is never part of a normal
// `assemble`/`test` run.
//
// This MUST stay a plain assignment (`=`), not a narrowing guard. The producer plugin's
// own `onTestBeforeVariants` callback ANDs its allow-list into `enable`, and it is
// registered from the `plugins {}` block — i.e. BEFORE this callback runs. Assignment
// here is therefore the last word, and it is what keeps BOTH the S9 macrobenchmark
// variant (`:benchmark:connectedBenchmarkAndroidTest`) and the S11 generation variants
// alive. Dropping `benchmark` from the set would silently delete the macrobenchmark
// suite; dropping the synthetic pair would leave `:app:generateReleaseBaselineProfile`
// with no producer variant to run.
androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType in servedBuildTypes
    }
}

// SDD android-stack-modernization S11: profile-collection settings. `useConnectedDevices`
// is the plugin's own default (true) and is stated explicitly because it IS the S11
// route: the profile is collected on a CONNECTED device — the CI emulator booted by the
// dispatch-only `android-baseline-profile` job — not on a Gradle Managed Device. No
// `managedDevices` list is declared on purpose: declaring one would make the plugin
// prefer a managed device over the emulator CI already boots.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.junit.ext)
    implementation(libs.testing.junit4)
}

// ── Benchmark regression gate ────────────────────────────────────────────────
// The device run emits `benchmarkMetrics/metrics-current.json` (BenchmarkMetricsExporter)
// into the instrumentation "additional test output" directory, which AGP pulls back
// under build/outputs/connected_android_test_additional_output/. This task compares
// that document against the checked-in baseline (`baseline/metrics-baseline.json`)
// and FAILS the build when any metric regresses past the threshold — a publish-only
// path is explicitly rejected by the design (the spec requires a failure).
//
// Device-free: the task itself is a pure JSON compare; it is executed by the S10
// emulator job after `:benchmark:connectedBenchmarkAndroidTest`.
val defaultRegressionThresholdPercent = 10.0

tasks.register("verifyBenchmarkRegression") {
    group = "verification"
    description = "Fails the build when a macrobenchmark metric regresses past its baseline threshold"

    val projectDirFile = layout.projectDirectory.asFile
    val metricsOverride = providers.gradleProperty("benchmarkMetricsFile")
    val baselineOverride = providers.gradleProperty("benchmarkBaselineFile")
    val thresholdOverride = providers.gradleProperty("benchmarkRegressionThresholdPercent")
    val defaultCurrent = layout.buildDirectory.file("outputs/benchmarkMetrics/metrics-current.json")
    val defaultBaseline = layout.projectDirectory.file("baseline/metrics-baseline.json")
    val outputsDir = layout.buildDirectory.dir("outputs")
    val taskLogger = logger

    doLast {
        // ── Baseline ─────────────────────────────────────────────────────────
        val baselineFile = baselineOverride.orNull?.let { projectDirFile.resolve(it) } ?: defaultBaseline.asFile
        if (!baselineFile.isFile) {
            throw GradleException("Benchmark baseline not found: ${baselineFile.absolutePath}")
        }
        val baseline = JsonSlurper().parse(baselineFile) as Map<*, *>

        // A placeholder baseline means no device run has established a real
        // threshold yet. Failing loudly (instead of passing against fabricated
        // numbers) is the only honest outcome — see baseline/metrics-baseline.json.
        if (baseline["placeholder"] == true) {
            throw GradleException(
                "Benchmark baseline is still a PLACEHOLDER — no device run has established it. " +
                    "Run :benchmark:connectedBenchmarkAndroidTest on the S10 emulator, replace " +
                    "${baselineFile.absolutePath} with the emitted metrics and set \"placeholder\": false.",
            )
        }

        val baselineMetrics =
            (baseline["metrics"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val number = (value as? Number)?.toDouble()
                    if (key is String && number != null) key to number else null
                }?.toMap()
                .orEmpty()
        if (baselineMetrics.isEmpty()) {
            throw GradleException("Baseline declares no numeric metrics: ${baselineFile.absolutePath}")
        }

        val thresholdPercent =
            thresholdOverride.orNull?.toDoubleOrNull()
                ?: (baseline["regressionThresholdPercent"] as? Number)?.toDouble()
                ?: defaultRegressionThresholdPercent

        // ── Current metrics (canonical path, or AGP's pulled output dir) ─────
        val explicit = metricsOverride.orNull?.let { projectDirFile.resolve(it) }
        val candidates =
            buildList {
                explicit?.let { add(it) }
                add(defaultCurrent.get().asFile)
                val root = outputsDir.get().asFile
                if (root.isDirectory) {
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.name == "metrics-current.json" }
                        .forEach { add(it) }
                }
            }
        val currentFile = candidates.firstOrNull { it.isFile }
        if (currentFile == null) {
            throw GradleException(
                "No benchmark metrics found (looked for ${defaultCurrent.get().asFile.absolutePath} and " +
                    "build/outputs/**/benchmarkMetrics/metrics-current.json). Run " +
                    ":benchmark:connectedBenchmarkAndroidTest on a device/emulator first, or pass " +
                    "-PbenchmarkMetricsFile=<path>.",
            )
        }
        val current = JsonSlurper().parse(currentFile) as Map<*, *>
        if (current["placeholder"] == true) {
            throw GradleException("Current benchmark metrics are a placeholder: ${currentFile.absolutePath}")
        }
        val currentMetrics =
            (current["metrics"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val number = (value as? Number)?.toDouble()
                    if (key is String && number != null) key to number else null
                }?.toMap()
                .orEmpty()

        // ── Compare ──────────────────────────────────────────────────────────
        // Baseline keys are metric SUFFIXES (e.g. `timeToInitialDisplayMs.p95`) because
        // the exporter's keys carry a benchmark-name prefix that the library owns.
        // Every matching current metric must stay within the threshold.
        val limitFactor = 1.0 + thresholdPercent / 100.0
        val violations = mutableListOf<String>()
        baselineMetrics.forEach { (key, baselineValue) ->
            val matches = currentMetrics.filterKeys { it == key || it.endsWith(".$key") }
            if (matches.isEmpty()) {
                violations += "$key: MISSING from current metrics (the journey did not run?)"
            }
            matches.forEach { (name, value) ->
                val limit = baselineValue * limitFactor
                if (value > limit) {
                    violations += "$name: $value > baseline $baselineValue (limit $limit at +$thresholdPercent%)"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Benchmark regression past $thresholdPercent% vs ${baselineFile.name}:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        taskLogger.lifecycle(
            "verifyBenchmarkRegression: OK — ${baselineMetrics.size} metric(s) within $thresholdPercent% of baseline.",
        )
    }
}

plugins {
    `kotlin-dsl`
    id("com.diffplug.spotless") version "7.2.1"
}

dependencies {
    // The precompiled script plugins resolve AGP and the Kotlin plugins through
    // this build's own classpath. The versions mirror `gradle/libs.versions.toml`
    // in the main build.
    implementation("com.android.tools.build:gradle:8.11.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
    // `nexo.kotlin.formatting` applies Spotless to every Kotlin module from one
    // place, so the plugin must be on this build's classpath. Version mirrors
    // `gradle/libs.versions.toml` (`spotless = "7.2.1"`), same as the entries
    // above mirror the catalog.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.2.1")

    // The licence-name normaliser is pure logic that decides how the shipped
    // inventory is grouped, so it is unit-tested in the build it runs in rather
    // than below the module it feeds.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// build-logic is an `includeBuild`, so the repository-wide `nexo.kotlin.formatting`
// plugin never reaches it (it is applied per module in the main build). This
// module is where the licence task and its normaliser live, so it carries its
// own identical gate instead of leaving those files unformatted.
spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude("build/**")
        ktlint()
    }
}

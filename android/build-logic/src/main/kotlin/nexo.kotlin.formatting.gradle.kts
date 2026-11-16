import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("com.diffplug.spotless")
}

// Single formatting contract for every Kotlin module in the build.
//
// Apply this in any module that compiles Kotlin — `nexo.android.library` and
// `nexo.android.library.compose` apply it for the Android libraries, and the
// pure-JVM `:core:*` modules apply the id directly. Adding it in one place is
// the point: a new module inherits the gate by applying its convention plugin,
// instead of copying a `spotless { }` block that nobody remembers to add.
//
// Deliberately NOT a root `subprojects { }` block:
//  - `build-logic` is an `includeBuild`, so a root block would never reach it.
//  - Cross-project configuration defeats isolated projects and is the classic
//    way to break `--configuration-cache` (enabled in gradle.properties).
//  - `com.diffplug.spotless` is declared `apply false` at the root, so
//    `subprojects { apply(plugin = "com.diffplug.spotless") }` would have no
//    plugin to resolve against the root classpath.
//
// The target patterns are MODULE-RELATIVE (`**/*.kt` is relative to each
// project dir), which is what makes this gate universal: applied from `:app`
// it covers `:app`, applied from `:core:data` it covers `:core:data`.
configure<SpotlessExtension> {
    kotlin {
        target("**/*.kt", "**/*.kts")
        // Konsist fixtures under test resources are parsed data, not compiled
        // source (see DirectViewModelConstructionFixture.kt) — keep them out
        // of ktlint. Harmless for modules that have no test resources.
        targetExclude("src/test/resources/**")
        // ktlint at the Spotless-bundled default version; detekt stays
        // analysis-authoritative. See android/.editorconfig for the rule
        // deferrals and the import-on-demand mirror of detekt's WildcardImport.
        ktlint()
    }
}

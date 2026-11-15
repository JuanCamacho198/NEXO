plugins {
    `kotlin-dsl`
}

dependencies {
    // The precompiled script plugins resolve AGP and the Kotlin plugins through
    // this build's own classpath. The versions mirror `gradle/libs.versions.toml`
    // in the main build.
    implementation("com.android.tools.build:gradle:8.11.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
}

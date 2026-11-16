plugins {
    alias(libs.plugins.kotlin.jvm)
    // `@Serializable` on CatalogBook / Device and `@SerialName` on Device are
    // real annotations compiled into this module's bytecode, so the serialization
    // compiler plugin runs here — the app applying it does NOT cover this module.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // `androidx.compose.runtime.Immutable` is a RUNTIME-retention annotation with no
    // Android dependency: the BOM supplies the version, the artifact resolves for a
    // plain JVM target. Declared as `compileOnly` because @Immutable only needs to be
    // on the compile classpath — consumers (the Compose compiler in :app) read it
    // from their own Compose runtime. Keeping it out of the published API/classpath
    // prevents a Compose runtime from leaking onto pure-JVM consumers.
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)

    api(libs.kotlinx.serialization.json)

        // Shared Android-free test fixtures (MainDispatcherRule, account fixtures).
    testImplementation(project(":core:test"))
testImplementation(kotlin("test"))
    testImplementation(libs.testing.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testing.json)
}

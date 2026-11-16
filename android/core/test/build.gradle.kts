// Shared test fixtures for the :core:* and :feature:* test source sets.
//
// A Kotlin/JVM module on purpose: everything here must stay free of Android and
// Room types so that every module in the build can depend on it, including the
// pure-JVM :core:model and :core:domain and the features that do not depend on
// :core:data (:feature:auth, :feature:highlights). A fixture that needs Android
// cannot live here without forcing those modules onto the Android stack, so
// `mockAppContext`, `FakeDaos` and the other Android-bound fakes stay in the
// Android modules that use them.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // ktlint gate from build-logic (one contract for every Kotlin module).
    id("nexo.kotlin.formatting")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // MainDispatcherRule is a JUnit TestWatcher that swaps Dispatchers.Main.
    api(libs.testing.junit4)
    // setMain/resetMain/TestDispatcher live in kotlinx-coroutines-test.
    api(libs.kotlinx.coroutines.test)

    // activeUserProviderFor builds a real ActiveUserProvider.
    api(project(":core:domain"))
}

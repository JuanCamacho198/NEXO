plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // `api`, not `implementation`: domain repository/usecase signatures are written
    // in terms of com.nexo.domain.model types, so any consumer of :core:domain must
    // also see :core:model on its compile classpath.
    api(project(":core:model"))

    // Flow / CoroutineDispatcher appear in repository and usecase signatures.
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.testing.junit4)
    testImplementation(libs.testing.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

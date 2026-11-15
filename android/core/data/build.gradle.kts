import java.util.Properties

plugins {
    id("nexo.android.library")
    // @Serializable on the DTOs lives in this module's bytecode, so the serialization
    // compiler plugin runs here — :app applying it does NOT cover this module.
    alias(libs.plugins.kotlin.serialization)
    // Room codegen: AppDatabase, its @Entity/@Dao set and the migrations all moved here.
    alias(libs.plugins.ksp)
}

ksp {
    // Room exports its schemas next to this module so migration validation keeps
    // resolving. `core/data/schemas` carries the history migrated from app/schemas.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// SupabaseClientProvider reads SUPABASE_URL / SUPABASE_ANON_KEY from this module's
// own BuildConfig. The values come from local.properties exactly as before; they are
// re-declared here because a library module cannot see :app's BuildConfig.
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

// SDD M3: data layer extracted from :app.
//
// An Android library (not a JVM module): it uses android.content.Context, Room,
// OkHttp/Ktor, Supabase, WorkManager and Android file APIs. Package names are
// unchanged (com.nexo.data.*), so no consumer import changed.
android {
    namespace = "com.nexo.data"

    defaultConfig {
        val supabaseUrl = (localProperties.getProperty("SUPABASE_URL") ?: "").escapeForBuildConfig()
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        val supabaseAnonKey = (localProperties.getProperty("SUPABASE_ANON_KEY") ?: "").escapeForBuildConfig()
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    // SupabaseClientProvider reads SUPABASE_URL / SUPABASE_ANON_KEY from this
    // module's own BuildConfig. The values come from local.properties exactly as
    // before; they are re-declared here because a library module cannot see
    // :app's BuildConfig.
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // Room schema history is read by MigrationTestHelper in unit tests.
        getByName("test").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

dependencies {
    // Domain types appear in data public signatures (repository impls implement
    // com.nexo.domain.repository interfaces), so consumers must see them too.
    api(project(":core:domain"))
    api(project(":core:model"))
    // Cross-cutting observability (DebugLog / DebugDual / SentryMetrics).
    implementation(project(":core:debug"))

    // Room — entities, DAOs, AppDatabase and the KSP processor all live here now.
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    // Bundled SQLite driver (FTS5): the platform SQLite has no fts5 module, so
    // MIGRATION_15_16 fails at open time on real devices without it.
    implementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.room.testing)

    // @HiltWorker on OutboxDrainWorker / RetentionPruneWorker. androidx-hilt-compiler
    // is the WorkManager bridge processor; it is separate from Dagger's hilt-compiler.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)

    // Paging 3 sources (PagingSource in the DAOs).
    implementation(libs.paging.runtime)

    // Networking: Ktor for the catalog clients, Supabase for sync/auth.
    implementation(libs.bundles.ktor.client)
    implementation(platform(libs.supabase.bom))
    implementation(libs.bundles.supabase)
    implementation(libs.kotlinx.datetime)

    // Google Drive REST API + Credential Manager (remote/drive, remote/google).
    implementation(libs.google.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.http.client.gson)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // Readium publication parsing (data/epub, cover extraction).
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)

    // Encrypted preferences (data/session).
    implementation(libs.androidx.security.crypto)

    // Coil image loader (CacheRepositoryImpl cover caching).
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Tests: Robolectric for the DAO/database suites + MockK for collaborators.
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.androidx.test.core)
    // Turbine — Flow/StateFlow emission assertions in SyncOrchestratorTest.
    testImplementation(libs.testing.turbine)
    // Real org.json on the JVM classpath: Android's stub throws "not mocked"
    // without it, which is exactly what the addon/catalog JSON suites hit.
    testImplementation(libs.testing.json)
}

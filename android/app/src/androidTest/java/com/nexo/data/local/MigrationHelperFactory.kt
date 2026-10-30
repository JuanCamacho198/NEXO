package com.nexo.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID

/**
 * Builds a [MigrationTestHelper] backed by a database file that no other helper instance shares.
 *
 * The helper opens its file lazily inside `createDatabase` / `runMigrationsAndValidate` and keeps
 * that handle open until JUnit calls its `finished()` hook, so a single fixed path carries state
 * from one method into the next. A later method that seeds the same schema then fails with
 * "table ... already exists", and one that expects a schema a migration would have created fails
 * with "no such table: ...". A unique path per helper removes the shared state entirely instead of
 * racing it with cleanup whose ordering depends on JUnit's rule/`@Before` sequence.
 *
 * The helper itself is returned as a normal [MigrationTestHelper], so it stays usable as a
 * `@Rule` field and JUnit still drives `starting()` / `finished()` on it — which is what closes
 * the connections the helper holds open.
 */
object MigrationHelperFactory {
    fun create(namePrefix: String): MigrationTestHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("$namePrefix-${UUID.randomUUID()}")
        // A previous aborted run cannot own this fresh UUID, but clearing defensively keeps the
        // helper's first `createDatabase` on a genuinely empty file.
        if (dbFile.exists()) dbFile.delete()
        return MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            dbFile,
            BundledSQLiteDriver(),
            AppDatabase::class,
            // Without an explicit factory the helper reflectively instantiates AppDatabase_Impl
            // with no driver and no configuration, so runMigrationsAndValidate resolves
            // auto-migration specs against an unconfigured instance.
            {
                Room
                    .inMemoryDatabaseBuilder(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        AppDatabase::class.java,
                    ).setDriver(BundledSQLiteDriver())
                    .build()
            },
        )
    }
}

package com.nexo.data.local

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the bundled SQLite driver ships the fts5 module MIGRATION_15_16 depends on.
 * Android's platform SQLite has no fts5 (SQLITE_ENABLE_FTS5 is not defined in any
 * AOSP build), so this DDL throws "no such module: fts5" under the platform driver.
 * Requires a device or emulator — the module is a native Android library.
 */
@RunWith(AndroidJUnit4::class)
class BundledSqliteFts5Test {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AppDatabase::class.java,
                ).setDriver(BundledSQLiteDriver())
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bundledDriver_supportsFts5CreateInsertAndMatch() =
        runBlocking {
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE VIRTUAL TABLE fts_probe USING fts5(x)") { it.step() }
                connection.usePrepared("INSERT INTO fts_probe(x) VALUES ('hello world')") { it.step() }
            }

            var matched = 0
            db.useReaderConnection { connection ->
                connection
                    .usePrepared("SELECT x FROM fts_probe WHERE fts_probe MATCH 'hello'") { statement ->
                        while (statement.step()) matched++
                    }
            }
            assertEquals(1, matched)
        }
}

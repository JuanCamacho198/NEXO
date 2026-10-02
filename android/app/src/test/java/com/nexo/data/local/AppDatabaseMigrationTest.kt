package com.nexo.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    private fun query(
        db: SupportSQLiteDatabase,
        sql: String,
    ): Long {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun testDbPath(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-26-27")
            .absolutePath

    private fun testDbPath27To28(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-27-28")
            .absolutePath

    private fun testDbPath28To29(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-28-29")
            .absolutePath

    @Test
    fun `migration 26 to 27 creates installed_addons and preserves existing rows`() {
        val dbPath = testDbPath()
        val outboxBefore: Long
        helper.createDatabase(dbPath, 26).use { db ->
            db.execSQL(
                "INSERT INTO discover_cache (key, payload, fetched_at, ttl_s) " +
                    "VALUES ('p:test:q:1', '{\"n\":1}', 1000, 86400)",
            )
            outboxBefore = query(db, "SELECT COUNT(*) FROM sync_outbox")
        }

        val db = helper.runMigrationsAndValidate(dbPath, 27, true, AppDatabaseMigrations.MIGRATION_26_27)

        db.query("SELECT payload FROM discover_cache WHERE `key` = 'p:test:q:1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{\"n\":1}", cursor.getString(0))
        }

        db.query("SELECT id, url, manifest_json, enabled, added_at FROM installed_addons").use { cursor ->
            assertEquals(0, cursor.count)
        }

        db.query("PRAGMA table_info(installed_addons)").use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names.add(cursor.getString(1))
            assertEquals(listOf("id", "url", "manifest_json", "enabled", "added_at"), names)
        }

        val outboxAfter = query(db, "SELECT COUNT(*) FROM sync_outbox")
        assertEquals(outboxBefore, outboxAfter)
        db.close()
    }

    @Test
    fun `migration 27 to 28 adds the ten evidence columns and preserves existing rows`() {
        val dbPath = testDbPath27To28()
        val outboxBefore: Long
        helper.createDatabase(dbPath, 27).use { db ->
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, definition) " +
                    "VALUES ('dw-mig', 'efimero', 7000, 'pre-existing definition')",
            )
            db.execSQL(
                "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload, created_at, retry_count) " +
                    "VALUES ('ob-mig', 'DICTIONARY_WORD', 'dw-mig', 'UPSERT', '{}', 1000, 0)",
            )
            outboxBefore = query(db, "SELECT COUNT(*) FROM sync_outbox")
        }

        val db = helper.runMigrationsAndValidate(dbPath, 28, true, AppDatabaseMigrations.MIGRATION_27_28)

        val evidenceColumns =
            listOf(
                "definition",
                "part_of_speech",
                "phonetic",
                "example",
                "quote",
                "source_book_id",
                "source_book_title",
                "source_book_author",
                "source_chapter",
                "source_locator",
            )
        val actualColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(dictionary_words)").use { cursor ->
            while (cursor.moveToNext()) actualColumns.add(cursor.getString(1))
        }
        evidenceColumns.forEach { column ->
            assertTrue("dictionary_words is missing the evidence column $column", actualColumns.contains(column))
        }

        db
            .query(
                "SELECT word, definition, part_of_speech, phonetic, example, quote, " +
                    "source_book_id, source_book_title, source_book_author, source_chapter, source_locator " +
                    "FROM dictionary_words WHERE id = 'dw-mig'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("efimero", cursor.getString(0))
                assertEquals("pre-existing definition", cursor.getString(1))
                for (index in 2 until cursor.columnCount) {
                    assertTrue("evidence column at index $index should be NULL after migration", cursor.isNull(index))
                }
            }

        val outboxAfter = query(db, "SELECT COUNT(*) FROM sync_outbox")
        assertEquals(outboxBefore, outboxAfter)
        assertEquals(1L, outboxAfter)
        db.close()
    }

    @Test
    fun `migration 28 to 29 adds the LWW write clock backfilled from the added time`() {
        val dbPath = testDbPath28To29()
        helper.createDatabase(dbPath, 28).use { db ->
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, definition) " +
                    "VALUES ('dw-lww', 'efimero', 7000, 'kept')",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 29, true, AppDatabaseMigrations.MIGRATION_28_29)

        db
            .query(
                "SELECT addedAtEpochMillis, updated_at_epoch_millis, definition " +
                    "FROM dictionary_words WHERE id = 'dw-lww'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7000L, cursor.getLong(0))
                assertEquals(7000L, cursor.getLong(1))
                assertEquals("kept", cursor.getString(2))
            }
        db.close()
    }
}

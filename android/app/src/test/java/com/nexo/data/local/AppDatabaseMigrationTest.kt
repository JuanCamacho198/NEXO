package com.nexo.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun testDbPath29To30(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-29-30")
            .absolutePath

    private fun testDbPath29To30VersionSkip(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-29-30-version-skip")
            .absolutePath

    private fun testDbPath29To30NoOp(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-29-30-noop")
            .absolutePath

    private fun testDbPath30To31(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-30-31")
            .absolutePath

    private fun testDbPath31To32(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-31-32")
            .absolutePath

    private fun testDbPath32To33(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-32-33")
            .absolutePath

    private fun testDbPath33To34(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-33-34")
            .absolutePath

    private fun testDbPath33To34Sessions(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-33-34-sessions")
            .absolutePath

    private fun testDbPath33To34Dictionary(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath("migration-33-34-dictionary")
            .absolutePath

    private fun columnNames(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val names = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(1))
        }
        return names
    }

    private fun progressPercentage(
        db: SupportSQLiteDatabase,
        bookId: String,
    ): Float? {
        db.query("SELECT percentage FROM reading_progress WHERE book_id = '$bookId'").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getFloat(0) else null
        }
    }

    /**
     * Seeds a pre-WU2b (v29) books row. `cacheAt == null` means the retired cache
     * column carried a NULL timestamp (unknown provenance).
     */
    private fun seedBook(
        db: SupportSQLiteDatabase,
        id: String,
        cachePct: Float,
        cacheAt: Long?,
    ) {
        db.execSQL(
            "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, " +
                "progress_percentage, progress_updated_at, state_version, remote_lifecycle, remote_catalog_version) " +
                "VALUES ('$id', '$id', '/$id.epub', 'epub', 1000, 'reading', $cachePct, ${cacheAt ?: "NULL"}, 0, 'imported', 0)",
        )
    }

    private fun seedCanonical(
        db: SupportSQLiteDatabase,
        bookId: String,
        percentage: Float,
        updatedAt: Long,
    ) {
        db.execSQL(
            "INSERT INTO reading_progress (id, book_id, cfi_location, percentage, current_page, updated_at, locator_json) " +
                "VALUES ('progress-$bookId', '$bookId', '', $percentage, NULL, $updatedAt, NULL)",
        )
    }

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

    @Test
    fun `migration 29 to 30 backfills every divergent position before dropping the cache column`() {
        val dbPath = testDbPath29To30()
        helper.createDatabase(dbPath, 29).use { db ->
            // Canonical newer: the stale cache value must NOT win.
            seedBook(db, "canonical-newer", cachePct = 10f, cacheAt = 1000L)
            seedCanonical(db, "canonical-newer", percentage = 50f, updatedAt = 5000L)
            // Cache newer: offline read only in the cache must be pushed to canonical.
            seedBook(db, "cache-newer", cachePct = 80f, cacheAt = 8000L)
            seedCanonical(db, "cache-newer", percentage = 20f, updatedAt = 3000L)
            // Cache-only: no canonical row at all.
            seedBook(db, "cache-only", cachePct = 33f, cacheAt = 2000L)
            // Canonical-only: cache carries no position.
            seedBook(db, "canonical-only", cachePct = 0f, cacheAt = null)
            seedCanonical(db, "canonical-only", percentage = 66f, updatedAt = 6000L)
            // NULL cache timestamp with real progress: unknown provenance, cache wins (W4).
            seedBook(db, "null-timestamp", cachePct = 55f, cacheAt = null)
            seedCanonical(db, "null-timestamp", percentage = 10f, updatedAt = 6000L)
            // Zero cache, no canonical: nothing to move.
            seedBook(db, "zero", cachePct = 0f, cacheAt = null)
        }

        val db = helper.runMigrationsAndValidate(dbPath, 30, true, AppDatabaseMigrations.MIGRATION_29_30)

        assertFalse("progress_percentage must be dropped", columnNames(db, "books").contains("progress_percentage"))
        assertEquals(50.0, progressPercentage(db, "canonical-newer")!!.toDouble(), 0.001)
        assertEquals(80.0, progressPercentage(db, "cache-newer")!!.toDouble(), 0.001)
        assertEquals(33.0, progressPercentage(db, "cache-only")!!.toDouble(), 0.001)
        assertEquals(66.0, progressPercentage(db, "canonical-only")!!.toDouble(), 0.001)
        assertEquals(55.0, progressPercentage(db, "null-timestamp")!!.toDouble(), 0.001)
        assertNull(progressPercentage(db, "zero"))
        assertEquals(5L, query(db, "SELECT COUNT(*) FROM reading_progress"))
        assertEquals(6L, query(db, "SELECT COUNT(*) FROM books"))
        db.close()
    }

    @Test
    fun `migration 29 to 30 is version-skip safe and needs no app-start backfill runner`() {
        val dbPath = testDbPath29To30VersionSkip()
        helper.createDatabase(dbPath, 29).use { db ->
            // A device jumping straight from a pre-WU2a build carries the only copy of
            // this position in the retired cache column; it was never backfilled.
            seedBook(db, "skip", cachePct = 42f, cacheAt = 2000L)
        }

        // Only the migration path runs: no ProgressBackfillRunner, no MainActivity and
        // no app lifecycle is involved, so the upgrade is self-sufficient.
        val db = helper.runMigrationsAndValidate(dbPath, 30, true, AppDatabaseMigrations.MIGRATION_29_30)

        assertFalse(columnNames(db, "books").contains("progress_percentage"))
        assertEquals(42.0, progressPercentage(db, "skip")!!.toDouble(), 0.001)
        db.close()
    }

    @Test
    fun `migration 29 to 30 is a no-op backfill when there is nothing to move`() {
        val dbPath = testDbPath29To30NoOp()
        helper.createDatabase(dbPath, 29).use { db ->
            seedBook(db, "untouched", cachePct = 0f, cacheAt = null)
        }

        val db = helper.runMigrationsAndValidate(dbPath, 30, true, AppDatabaseMigrations.MIGRATION_29_30)

        assertFalse(columnNames(db, "books").contains("progress_percentage"))
        assertNull(progressPercentage(db, "untouched"))
        assertEquals(0L, query(db, "SELECT COUNT(*) FROM reading_progress"))
        assertEquals(1L, query(db, "SELECT COUNT(*) FROM books"))
        db.close()
    }

    @Test
    fun `migration 30 to 31 backfills the indexed normalized key and adds both indexes`() {
        val dbPath = testDbPath30To31()
        helper.createDatabase(dbPath, 30).use { db ->
            // NFD stripping + case/space folding can only run in Kotlin, so the
            // backfill must reproduce the shared normalizer for existing rows.
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, definition, updated_at_epoch_millis) " +
                    "VALUES ('dw-norm', '  Café  ', 7000, 'coffee', 7000)",
            )
            db.execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, " +
                    "state_version, remote_lifecycle, remote_catalog_version) " +
                    "VALUES ('bk-norm', 'Book', '/b.epub', 'epub', 1000, 'reading', 0, 'imported', 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 31, true, AppDatabaseMigrations.MIGRATION_30_31)

        db
            .query("SELECT word, word_normalized, definition FROM dictionary_words WHERE id = 'dw-norm'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("  Café  ", cursor.getString(0))
                assertEquals("cafe", cursor.getString(1))
                assertEquals("coffee", cursor.getString(2))
            }

        val indexes =
            mutableListOf<String>()
        db
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('dictionary_words','books')")
            .use { cursor ->
                while (cursor.moveToNext()) indexes.add(cursor.getString(0))
            }
        assertTrue(
            "expected index_dictionary_words_word_normalized, got $indexes",
            indexes.contains("index_dictionary_words_word_normalized"),
        )
        assertTrue("expected index_books_reading_state, got $indexes", indexes.contains("index_books_reading_state"))
        db.close()
    }

    @Test
    fun `migration 31 to 32 creates the notifications table with its index`() {
        val dbPath = testDbPath31To32()
        helper.createDatabase(dbPath, 31).use { db ->
            // Pre-existing data must survive the additive migration untouched.
            db.execSQL(
                "INSERT INTO installed_addons (id, url, manifest_json, enabled, added_at) " +
                    "VALUES ('ad-1', 'https://a.example.com/m.json', '{}', 1, 1000)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 32, true, AppDatabaseMigrations.MIGRATION_31_32)

        assertEquals(
            listOf(
                "id",
                "created_at",
                "source",
                "category",
                "severity",
                "interruption",
                "i18n_key",
                "i18n_params",
                "target_kind",
                "target_id",
                "read_at",
                "dedup_key",
            ),
            columnNames(db, "notifications"),
        )
        assertEquals(0L, query(db, "SELECT COUNT(*) FROM notifications"))

        db.execSQL(
            "INSERT INTO notifications (id, created_at, source, category, severity, interruption, i18n_key, read_at) " +
                "VALUES ('n-1', 5000, 'SYNC', 'SYSTEM', 'INFO', 'IN_APP', 'notifications.event.sync.success', NULL)",
        )
        assertEquals(1L, query(db, "SELECT COUNT(*) FROM notifications"))

        val indexes = mutableListOf<String>()
        db
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'notifications'")
            .use { cursor ->
                while (cursor.moveToNext()) indexes.add(cursor.getString(0))
            }
        assertTrue("expected index_notifications_created_at, got $indexes", indexes.contains("index_notifications_created_at"))

        assertEquals(1L, query(db, "SELECT COUNT(*) FROM installed_addons"))
        db.close()
    }

    @Test
    fun `migration 32 to 33 recreates the six account-scoped tables with user_id`() {
        val dbPath = testDbPath32To33()
        helper.createDatabase(dbPath, 32).use { db ->
            // Nothing is preserved (decision D1), so the pre-existing rows only need
            // to survive the DROP long enough for the recreate to run.
            db.execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, " +
                    "state_version, remote_lifecycle, remote_catalog_version) " +
                    "VALUES ('bk-33', 'Book', '/b.epub', 'epub', 1000, 'reading', 0, 'imported', 0)",
            )
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized) " +
                    "VALUES ('dw-33', 'word', 1000, 1000, 'word')",
            )
            db.execSQL(
                "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload, created_at, retry_count) " +
                    "VALUES ('so-33', 'BOOK', 'bk-33', 'CREATE', '{}', 1000, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 33, true, AppDatabaseMigrations.MIGRATION_32_33)

        val expectedUserIdIndexes =
            mapOf(
                "books" to "index_books_user_id_deleted_at_updated_at",
                "reading_progress" to "index_reading_progress_user_id_book_id",
                "highlights" to "index_highlights_user_id_book_id_deleted_at",
                "bookmarks" to "index_bookmarks_user_id_book_id_deleted_at",
                "dictionary_words" to "index_dictionary_words_word_normalized",
                "sync_outbox" to "index_sync_outbox_user_id",
            )

        for ((table, _) in expectedUserIdIndexes) {
            assertEquals("$table must gain a user_id column", "user_id", columnNames(db, table).last())
        }

        val indexes = mutableListOf<String>()
        db
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('books','reading_progress','highlights','bookmarks','dictionary_words','sync_outbox')")
            .use { cursor ->
                while (cursor.moveToNext()) indexes.add(cursor.getString(0))
            }
        for ((_, index) in expectedUserIdIndexes) {
            assertTrue("expected $index, got $indexes", indexes.contains(index))
        }

        assertEquals(0L, query(db, "SELECT COUNT(*) FROM books"))
        assertEquals(0L, query(db, "SELECT COUNT(*) FROM dictionary_words"))
        assertEquals(0L, query(db, "SELECT COUNT(*) FROM sync_outbox"))
        db.close()
    }

    @Test
    fun `migration 33 to 34 gives reading_stats an owner-scoped primary key`() {
        val dbPath = testDbPath33To34()
        helper.createDatabase(dbPath, 33).use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, " +
                    "state_version, remote_lifecycle, remote_catalog_version, user_id) " +
                    "VALUES ('bk-stats', 'Book', '/b.epub', 'epub', 1000, 'reading', 0, 'imported', 0, 'user-a')",
            )
            // The v33 primary key is `book_id` alone, so a second owner's row for
            // the same book cannot even be inserted here — that IS the defect.
            db.execSQL(
                "INSERT INTO reading_stats (book_id, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, userId) " +
                    "VALUES ('bk-stats', 30, 2000, 2, 'user-a')",
            )
        }

        // No rows are carried over (decision: discard existing reading_stats).
        val db = helper.runMigrationsAndValidate(dbPath, 34, true, AppDatabaseMigrations.MIGRATION_33_34)

        assertEquals(0L, query(db, "SELECT COUNT(*) FROM reading_stats"))
        assertEquals(
            listOf("book_id", "totalMinutesRead", "lastReadDateEpochMillis", "sessionsCount", "user_id"),
            columnNames(db, "reading_stats"),
        )

        // Both owners can now hold stats for the same book simultaneously.
        db.execSQL(
            "INSERT INTO reading_stats (book_id, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, user_id) " +
                "VALUES ('bk-stats', 30, 2000, 2, 'user-a')",
        )
        db.execSQL(
            "INSERT INTO reading_stats (book_id, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, user_id) " +
                "VALUES ('bk-stats', 45, 3000, 3, 'user-b')",
        )
        assertEquals(2L, query(db, "SELECT COUNT(*) FROM reading_stats"))

        db
            .query(
                "SELECT totalMinutesRead FROM reading_stats WHERE book_id = 'bk-stats' AND user_id = 'user-a'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(30L, cursor.getLong(0))
            }
        db
            .query(
                "SELECT totalMinutesRead FROM reading_stats WHERE book_id = 'bk-stats' AND user_id = 'user-b'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(45L, cursor.getLong(0))
            }

        // A same-owner re-upsert still REPLACEs its own row, not the other's.
        db.execSQL(
            "INSERT OR REPLACE INTO reading_stats (book_id, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, user_id) " +
                "VALUES ('bk-stats', 99, 4000, 4, 'user-a')",
        )
        assertEquals(2L, query(db, "SELECT COUNT(*) FROM reading_stats"))
        assertEquals(
            45L,
            query(db, "SELECT totalMinutesRead FROM reading_stats WHERE book_id = 'bk-stats' AND user_id = 'user-b'"),
        )

        db.close()
    }

    @Test
    fun `migration 33 to 34 renames the owner column on both reading tables`() {
        val dbPath = testDbPath33To34Sessions()
        helper.createDatabase(dbPath, 33).use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, " +
                    "state_version, remote_lifecycle, remote_catalog_version, user_id) " +
                    "VALUES ('bk-sess', 'Book', '/b.epub', 'epub', 1000, 'reading', 0, 'imported', 0, 'user-a')",
            )
            db.execSQL(
                "INSERT INTO reading_sessions (id, book_id, start_time, duration_minutes, date, userId, updated_at_epoch_millis) " +
                    "VALUES ('rs-1', 'bk-sess', 500, 10, 20240101, 'user-a', 7000)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 34, true, AppDatabaseMigrations.MIGRATION_33_34)

        assertFalse("reading_sessions.userId is gone", columnNames(db, "reading_sessions").contains("userId"))
        assertTrue("reading_sessions.user_id exists", columnNames(db, "reading_sessions").contains("user_id"))
        assertFalse("reading_stats.userId is gone", columnNames(db, "reading_stats").contains("userId"))
        assertTrue("reading_stats.user_id exists", columnNames(db, "reading_stats").contains("user_id"))

        // Existing sessions survive the recreate with their owner intact.
        db.query("SELECT user_id, duration_minutes FROM reading_sessions WHERE id = 'rs-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user-a", cursor.getString(0))
            assertEquals(10, cursor.getInt(1))
        }
        assertEquals(1L, query(db, "SELECT COUNT(*) FROM reading_sessions"))

        val indexes = mutableListOf<String>()
        db
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reading_sessions'")
            .use { cursor ->
                while (cursor.moveToNext()) indexes.add(cursor.getString(0))
            }
        assertTrue(
            "expected index_reading_sessions_date_user_id, got $indexes",
            indexes.contains("index_reading_sessions_date_user_id"),
        )

        db.close()
    }

    @Test
    fun `migration 33 to 34 deduplicates dictionary words before the unique index`() {
        val dbPath = testDbPath33To34Dictionary()
        helper.createDatabase(dbPath, 33).use { db ->
            // Three rows collapse to one: the newest write clock wins, and a tie
            // on the clock falls back to the newest "first added" time.
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized, user_id) " +
                    "VALUES ('dw-old', 'cafe', 1000, 1000, 'cafe', 'user-a')",
            )
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized, user_id) " +
                    "VALUES ('dw-new', 'café', 2000, 5000, 'cafe', 'user-a')",
            )
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized, user_id) " +
                    "VALUES ('dw-tie', 'cafe', 3000, 5000, 'cafe', 'user-a')",
            )
            // A different owner keeps the same normalized word.
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized, user_id) " +
                    "VALUES ('dw-other', 'cafe', 1500, 1500, 'cafe', 'user-b')",
            )
            // A different word is untouched.
            db.execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, updated_at_epoch_millis, word_normalized, user_id) " +
                    "VALUES ('dw-tea', 'tea', 1000, 1000, 'tea', 'user-a')",
            )
        }

        val db = helper.runMigrationsAndValidate(dbPath, 34, true, AppDatabaseMigrations.MIGRATION_33_34)

        assertEquals(3L, query(db, "SELECT COUNT(*) FROM dictionary_words"))
        db.query("SELECT id, word FROM dictionary_words WHERE user_id = 'user-a' AND word_normalized = 'cafe'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("dw-tie", cursor.getString(0))
            assertEquals("cafe", cursor.getString(1))
        }
        assertEquals(
            1L,
            query(db, "SELECT COUNT(*) FROM dictionary_words WHERE user_id = 'user-b' AND word_normalized = 'cafe'"),
        )

        // The index is now unique for (user_id, word_normalized).
        db.query("PRAGMA index_list(dictionary_words)").use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            val uniqueCol = cursor.getColumnIndex("unique")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == "index_dictionary_words_user_id_word_normalized") {
                    found = true
                    assertEquals(1, cursor.getInt(uniqueCol))
                }
            }
            assertTrue("expected index_dictionary_words_user_id_word_normalized", found)
        }

        db.close()
    }
}

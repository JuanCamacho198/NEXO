package com.nexo.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrate4To5_preservesExistingBookRows() {
        val dbName = "migration-test"

        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    format TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO books (id, title, author, cover_path, file_path, format, updated_at)
                VALUES ('book-1', 'Title 1', 'Author 1', NULL, '/tmp/book-1.epub', 'epub', 123)
                """.trimIndent(),
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                dbName,
                5,
                true,
                AppDatabaseMigrations.MIGRATION_4_5,
            ).query("SELECT id, deleted_at FROM books WHERE id = 'book-1'")
            .use { cursor ->
                check(cursor.moveToFirst()) { "Expected migrated book row to exist" }
                val deletedAtColumn = cursor.getColumnIndex("deleted_at")
                check(deletedAtColumn >= 0) { "Expected deleted_at column to exist" }
                check(cursor.isNull(deletedAtColumn)) { "Expected deleted_at to be null for existing rows" }
            }
    }

    @Test
    fun migrate4To5_usingRegisteredMigrations_succeeds() {
        val dbName = "migration-test-all"

        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    format TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                dbName,
                5,
                true,
                *AppDatabaseMigrations.ALL,
            ).query("PRAGMA table_info(books)")
            .use { cursor ->
                var hasDeletedAt = false
                val nameColumn = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == "deleted_at") {
                        hasDeletedAt = true
                        break
                    }
                }
                assertEquals(true, hasDeletedAt)
            }
    }

    @Test
    fun migrate15To16_renamesReadingStatsColumnAndAddsFk() {
        val dbName = "migration-test-15-16"

        // Seed v15 with: book, reading_stats row using the old camelCase column,
        // a sync_file_mappings row, a sync_outbox row, and a reading_sessions row.
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    format TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at) VALUES ('book-1', 'B1', '/b1.epub', 'epub', 100)",
            )

            // reading_stats in v15 uses camelCase column `bookId`
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS reading_stats (
                    bookId TEXT NOT NULL,
                    totalMinutesRead INTEGER NOT NULL,
                    lastReadDateEpochMillis INTEGER NOT NULL,
                    sessionsCount INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    PRIMARY KEY(bookId)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO reading_stats VALUES ('book-1', 42, 200, 3, '')",
            )

            // sync_file_mappings in v15 has no FK
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_file_mappings (
                    drive_file_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(drive_file_id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO sync_file_mappings VALUES ('drive-1', 'u1', 'book-1', '/local/path', 300)",
            )

            // sync_outbox in v15 has no FK
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_outbox (
                    id TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    retry_count INTEGER NOT NULL,
                    last_error TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO sync_outbox VALUES ('out-1', 'BOOK', 'book-1', 'UPDATE', '{}', 400, 0, NULL)",
            )

            // reading_sessions
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS reading_sessions (
                    id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    start_time INTEGER NOT NULL,
                    duration_minutes INTEGER NOT NULL,
                    date INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO reading_sessions VALUES ('sess-1', 'book-1', 500, 10, 20240101, '')",
            )

            // dictionary_words (required by FTS5 content= link)
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS dictionary_words (
                    id TEXT NOT NULL,
                    word TEXT NOT NULL,
                    addedAtEpochMillis INTEGER NOT NULL,
                    definition TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO dictionary_words VALUES ('dw-1', 'hello', 600, 'a greeting')",
            )

            close()
        }

        // Run v15 → v16 migration
        val db =
            helper.runMigrationsAndValidate(
                dbName,
                16,
                true,
                AppDatabaseMigrations.MIGRATION_15_16,
            )

        // ── R3: reading_stats column renamed, no camelCase remains
        db.query("PRAGMA table_info(reading_stats)").use { cursor ->
            val nameColumn = cursor.getColumnIndex("name")
            var hasBookIdCamel = false
            var hasBookIdSnake = false
            while (cursor.moveToNext()) {
                when (cursor.getString(nameColumn)) {
                    "bookId" -> hasBookIdCamel = true
                    "book_id" -> hasBookIdSnake = true
                }
            }
            assertEquals(false, hasBookIdCamel)
            assertEquals(true, hasBookIdSnake)
        }

        // ── R3 continued: row count preserved
        db.query("SELECT COUNT(*) FROM reading_stats").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }

        // ── R1: reading_stats has FK → books(id) ON DELETE CASCADE
        db.query("PRAGMA foreign_key_list(reading_stats)").use { cursor ->
            val tableCol = cursor.getColumnIndex("table")
            val fromCol = cursor.getColumnIndex("from")
            val toCol = cursor.getColumnIndex("to")
            val onDeleteCol = cursor.getColumnIndex("on_delete")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(tableCol) == "books" &&
                    cursor.getString(fromCol) == "book_id" &&
                    cursor.getString(toCol) == "id" &&
                    cursor.getString(onDeleteCol) == "CASCADE"
                ) {
                    found = true
                    break
                }
            }
            assertEquals(true, found)
        }

        // ── R1 continued: sync_file_mappings has FK → books(id) ON DELETE CASCADE
        db.query("PRAGMA foreign_key_list(sync_file_mappings)").use { cursor ->
            val tableCol = cursor.getColumnIndex("table")
            val fromCol = cursor.getColumnIndex("from")
            val onDeleteCol = cursor.getColumnIndex("on_delete")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(tableCol) == "books" &&
                    cursor.getString(fromCol) == "book_id" &&
                    cursor.getString(onDeleteCol) == "CASCADE"
                ) {
                    found = true
                    break
                }
            }
            assertEquals(true, found)
        }

        // ── R7: sync_outbox has FK → books(id) ON DELETE SET NULL on entity_id
        db.query("PRAGMA foreign_key_list(sync_outbox)").use { cursor ->
            val tableCol = cursor.getColumnIndex("table")
            val fromCol = cursor.getColumnIndex("from")
            val onDeleteCol = cursor.getColumnIndex("on_delete")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(tableCol) == "books" &&
                    cursor.getString(fromCol) == "entity_id" &&
                    cursor.getString(onDeleteCol) == "SET NULL"
                ) {
                    found = true
                    break
                }
            }
            assertEquals(true, found)
        }

        // ── R2: composite indexes exist
        db.query("PRAGMA index_list(books)").use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            var hasBooksIdx = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == "index_books_deleted_at_updated_at") {
                    hasBooksIdx = true
                    break
                }
            }
            assertEquals(true, hasBooksIdx)
        }
        db.query("PRAGMA index_list(highlights)").use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            var hasIdx = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == "index_highlights_book_id_deleted_at") {
                    hasIdx = true
                    break
                }
            }
            assertEquals(true, hasIdx)
        }
        db.query("PRAGMA index_list(reading_sessions)").use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            var hasIdx = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == "index_reading_sessions_date_userId") {
                    hasIdx = true
                    break
                }
            }
            assertEquals(true, hasIdx)
        }

        // ── R6: FTS5 virtual table exists
        db
            .query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='dictionary_words_fts'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Expected dictionary_words_fts virtual table to exist" }
            }

        // ── All tables preserved with row counts
        db.query("SELECT COUNT(*) FROM sync_file_mappings").use { c ->
            check(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM sync_outbox").use { c ->
            check(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM reading_sessions").use { c ->
            check(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }

        db.close()
    }

    @Test
    fun migrate17To18_backfillsCanonicalReadingState() {
        val dbName = "migration-test-17-18"
        helper.createDatabase(dbName, 17).apply {
            execSQL(
                """
                CREATE TABLE books (
                    id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, author TEXT,
                    cover_path TEXT, file_path TEXT NOT NULL, format TEXT NOT NULL,
                    updated_at INTEGER NOT NULL, deleted_at INTEGER, status TEXT,
                    content_hash TEXT
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO books VALUES ('zero', 'Zero', NULL, NULL, '/zero', 'epub', 1, NULL, NULL, NULL)")
            execSQL("INSERT INTO books VALUES ('reading', 'Reading', NULL, NULL, '/reading', 'epub', 2, NULL, 'reading', NULL)")
            execSQL("INSERT INTO books VALUES ('done', 'Done', NULL, NULL, '/done', 'epub', 3, NULL, 'completed', NULL)")
            close()
        }

        helper
            .runMigrationsAndValidate(dbName, 18, true, AppDatabaseMigrations.MIGRATION_17_18)
            .query("SELECT id, reading_state FROM books ORDER BY id")
            .use { cursor ->
                val states =
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                assertEquals(listOf("completed", "reading", "to_read"), states)
            }
    }

    @Test
    fun migrate22To23_addsUpdatedAtColumnAndPreservesRows() {
        val dbName = "migration-test-22-23"

        // Seed a v22 database with a book + a legacy reading_sessions row.
        helper.createDatabase(dbName, 22).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    format TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    status TEXT,
                    content_hash TEXT,
                    reading_state TEXT NOT NULL DEFAULT 'to_read',
                    started_at INTEGER,
                    completed_at INTEGER,
                    progress_percentage REAL NOT NULL DEFAULT 0,
                    progress_updated_at INTEGER,
                    state_version INTEGER NOT NULL DEFAULT 0,
                    remote_file_id TEXT,
                    remote_path TEXT,
                    remote_lifecycle TEXT NOT NULL DEFAULT 'imported',
                    remote_catalog_version INTEGER NOT NULL DEFAULT 0,
                    remote_cover_ref TEXT,
                    remote_provider TEXT,
                    remote_protocol_version INTEGER,
                    total_pages INTEGER,
                    user_rating INTEGER,
                    description TEXT,
                    chapter_count INTEGER
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state) VALUES ('book-1', 'B1', '/b1.epub', 'epub', 100, 'reading')",
            )

            // v22 reading_sessions — NO updated_at_epoch_millis column yet.
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS reading_sessions (
                    id TEXT NOT NULL PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    start_time INTEGER NOT NULL,
                    duration_minutes INTEGER NOT NULL,
                    date INTEGER NOT NULL,
                    userId TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO reading_sessions VALUES ('sess-1', 'book-1', 500, 10, 20240101, '')")
            close()
        }

        val db =
            helper.runMigrationsAndValidate(
                dbName,
                23,
                true,
                AppDatabaseMigrations.MIGRATION_22_23,
            )

        // R1: the LWW column exists after migration.
        db.query("PRAGMA table_info(reading_sessions)").use { cursor ->
            var hasColumn = false
            val nameColumn = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == "updated_at_epoch_millis") {
                    hasColumn = true
                }
            }
            assertEquals(true, hasColumn)
        }

        // R2: existing rows are preserved with the default clock (0).
        db.query("SELECT id, updated_at_epoch_millis FROM reading_sessions").use { cursor ->
            check(cursor.moveToFirst()) { "Expected migrated reading_sessions row to exist" }
            assertEquals("sess-1", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
        }

        db.close()
    }

    @Test
    fun migrate23To24_addsMetadataColumnsAndDoublesRatings() {
        val dbName = "migration-test-23-24"

        // Seed a v23 database with the exact books schema exported in 23.json,
        // plus rows covering rated (1..5) and unrated (NULL) books.
        helper.createDatabase(dbName, 23).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_path TEXT,
                    file_path TEXT NOT NULL,
                    format TEXT NOT NULL,
                    total_pages INTEGER,
                    chapter_count INTEGER,
                    description TEXT,
                    user_rating INTEGER,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    status TEXT,
                    content_hash TEXT,
                    reading_state TEXT NOT NULL,
                    started_at INTEGER,
                    completed_at INTEGER,
                    progress_percentage REAL NOT NULL,
                    progress_updated_at INTEGER,
                    state_version INTEGER NOT NULL,
                    remote_file_id TEXT,
                    remote_path TEXT,
                    remote_lifecycle TEXT NOT NULL,
                    remote_catalog_version INTEGER NOT NULL,
                    remote_cover_ref TEXT,
                    remote_provider TEXT,
                    remote_protocol_version INTEGER,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL("CREATE INDEX IF NOT EXISTS index_books_deleted_at_updated_at ON books(deleted_at, updated_at DESC)")
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, user_rating) VALUES ('r1', 'R1', '/r1.epub', 'epub', 1, 'to_read', 1)",
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, user_rating) VALUES ('r3', 'R3', '/r3.epub', 'epub', 2, 'to_read', 3)",
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, user_rating) VALUES ('r5', 'R5', '/r5.epub', 'epub', 3, 'to_read', 5)",
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, reading_state, user_rating) VALUES ('r0', 'R0', '/r0.epub', 'epub', 4, 'to_read', NULL)",
            )
            close()
        }

        val db =
            helper.runMigrationsAndValidate(
                dbName,
                24,
                true,
                AppDatabaseMigrations.MIGRATION_23_24,
            )

        // R1: the 5 new metadata columns exist and are nullable.
        db.query("PRAGMA table_info(books)").use { cursor ->
            val nameColumn = cursor.getColumnIndex("name")
            val notNullColumn = cursor.getColumnIndex("notnull")
            val expected =
                mapOf(
                    "genre" to false,
                    "language" to false,
                    "publisher" to false,
                    "tags" to false,
                    "published_date" to false,
                )
            val actual = mutableMapOf<String, Boolean>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                if (expected.containsKey(name)) {
                    actual[name] = cursor.getInt(notNullColumn) == 1
                }
            }
            assertEquals(expected.keys, actual.keys)
            assertEquals(expected.values, expected.keys.map { actual[it] })
        }

        // R2: ratings reinterpreted as half-units (1→2, 3→6, 5→10); NULL stays NULL.
        db.query("SELECT id, user_rating FROM books ORDER BY id").use { cursor ->
            val ratings =
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to cursor.getLong(1).let { if (cursor.isNull(1)) null else it })
                    }
                }
            assertEquals(listOf("r0" to null, "r1" to 2L, "r3" to 6L, "r5" to 10L), ratings)
        }

        // R3: no row loss across the migration.
        db.query("SELECT COUNT(*) FROM books").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(0))
        }

        db.close()
    }
}

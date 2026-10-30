package com.nexo.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val migrationDbFile =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            migrationDbFile,
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

    @Before
    fun resetMigrationDatabase() {
        // The helper opens one fixed path and never deletes it, so a version written by an
        // earlier test method in this class survives into the next one and Room tries to
        // migrate backwards (e.g. "from 23 to 15").
        migrationDbFile.delete()
    }

    private fun SQLiteConnection.count(sql: String): Long =
        prepare(sql).use { statement ->
            check(statement.step()) { "count query returned no row: $sql" }
            statement.getLong(0)
        }

    private fun SQLiteConnection.columnNames(table: String): List<String> =
        prepare("PRAGMA table_info($table)").use { statement ->
            val nameIndex = statement.getColumnNames().indexOf("name")
            buildList {
                while (statement.step()) add(statement.getText(nameIndex))
            }
        }

    /** PRAGMA table_info rows as `name` to `notnull` (0/1). */
    private fun SQLiteConnection.tableInfo(table: String): Map<String, Int> =
        prepare("PRAGMA table_info($table)").use { statement ->
            val nameIndex = statement.getColumnNames().indexOf("name")
            val notNullIndex = statement.getColumnNames().indexOf("notnull")
            buildMap {
                while (statement.step()) {
                    put(statement.getText(nameIndex), statement.getInt(notNullIndex))
                }
            }
        }

    private data class ForeignKey(
        val table: String,
        val from: String,
        val to: String?,
        val onDelete: String,
    )

    private fun SQLiteConnection.foreignKeys(
        table: String,
        releaser: (SQLiteStatement) -> ForeignKey,
    ): List<ForeignKey> =
        prepare("PRAGMA foreign_key_list($table)").use { statement ->
            buildList {
                while (statement.step()) add(releaser(statement))
            }
        }

    private fun SQLiteConnection.indexNames(table: String): List<String> =
        prepare("PRAGMA index_list($table)").use { statement ->
            val nameIndex = statement.getColumnNames().indexOf("name")
            buildList {
                while (statement.step()) add(statement.getText(nameIndex))
            }
        }

    @Test
    fun migrate4To5_preservesExistingBookRows() {
        helper.createDatabase(4).apply {
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
            .runMigrationsAndValidate(5, listOf(AppDatabaseMigrations.MIGRATION_4_5))
            .prepare("SELECT id, deleted_at FROM books WHERE id = 'book-1'")
            .use { statement ->
                check(statement.step()) { "Expected migrated book row to exist" }
                val deletedAtIndex = statement.getColumnNames().indexOf("deleted_at")
                check(deletedAtIndex >= 0) { "Expected deleted_at column to exist" }
                check(statement.isNull(deletedAtIndex)) { "Expected deleted_at to be null for existing rows" }
            }
    }

    @Test
    fun migrate4To5_usingRegisteredMigrations_succeeds() {
        helper.createDatabase(4).apply {
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
            .runMigrationsAndValidate(5, AppDatabaseMigrations.ALL.toList())
            .let { db ->
                assertEquals(true, db.columnNames("books").contains("deleted_at"))
            }
    }

    @Test
    fun migrate15To16_renamesReadingStatsColumnAndAddsFk() {
        // Seed v15 with: book, reading_stats row using the old camelCase column,
        // a sync_file_mappings row, a sync_outbox row, and a reading_sessions row.
        helper.createDatabase(15).apply {
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
                16,
                listOf(AppDatabaseMigrations.MIGRATION_15_16),
            )

        // ── R3: reading_stats column renamed, no camelCase remains
        val readingStatsColumns = db.columnNames("reading_stats")
        assertEquals(false, readingStatsColumns.contains("bookId"))
        assertEquals(true, readingStatsColumns.contains("book_id"))

        // ── R3 continued: row count preserved
        assertEquals(1L, db.count("SELECT COUNT(*) FROM reading_stats"))

        // ── R1: reading_stats has FK → books(id) ON DELETE CASCADE
        val readingStatsFks =
            db.foreignKeys("reading_stats") {
                val tableIndex = it.getColumnNames().indexOf("table")
                val fromIndex = it.getColumnNames().indexOf("from")
                val toIndex = it.getColumnNames().indexOf("to")
                val onDeleteIndex = it.getColumnNames().indexOf("on_delete")
                ForeignKey(
                    table = it.getText(tableIndex),
                    from = it.getText(fromIndex),
                    to = it.getText(toIndex),
                    onDelete = it.getText(onDeleteIndex),
                )
            }
        assertEquals(
            true,
            readingStatsFks.any { it.table == "books" && it.from == "book_id" && it.to == "id" && it.onDelete == "CASCADE" },
        )

        // ── R1 continued: sync_file_mappings has FK → books(id) ON DELETE CASCADE
        val mappingFks =
            db.foreignKeys("sync_file_mappings") {
                ForeignKey(
                    table = it.getText(it.getColumnNames().indexOf("table")),
                    from = it.getText(it.getColumnNames().indexOf("from")),
                    to = null,
                    onDelete = it.getText(it.getColumnNames().indexOf("on_delete")),
                )
            }
        assertEquals(
            true,
            mappingFks.any { it.table == "books" && it.from == "book_id" && it.onDelete == "CASCADE" },
        )

        // ── R7: sync_outbox has FK → books(id) ON DELETE SET NULL on entity_id
        val outboxFks =
            db.foreignKeys("sync_outbox") {
                ForeignKey(
                    table = it.getText(it.getColumnNames().indexOf("table")),
                    from = it.getText(it.getColumnNames().indexOf("from")),
                    to = null,
                    onDelete = it.getText(it.getColumnNames().indexOf("on_delete")),
                )
            }
        assertEquals(
            true,
            outboxFks.any { it.table == "books" && it.from == "entity_id" && it.onDelete == "SET NULL" },
        )

        // ── R2: composite indexes exist
        assertEquals(true, db.indexNames("books").contains("index_books_deleted_at_updated_at"))
        assertEquals(true, db.indexNames("highlights").contains("index_highlights_book_id_deleted_at"))
        assertEquals(true, db.indexNames("reading_sessions").contains("index_reading_sessions_date_userId"))

        // ── R6: FTS5 virtual table exists
        db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='dictionary_words_fts'").use {
            check(it.step()) { "Expected dictionary_words_fts virtual table to exist" }
        }

        // ── All tables preserved with row counts
        assertEquals(1L, db.count("SELECT COUNT(*) FROM sync_file_mappings"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM reading_sessions"))

        db.close()
    }

    @Test
    fun migrate17To18_backfillsCanonicalReadingState() {
        helper.createDatabase(17).apply {
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
            .runMigrationsAndValidate(18, listOf(AppDatabaseMigrations.MIGRATION_17_18))
            .prepare("SELECT id, reading_state FROM books ORDER BY id")
            .use { statement ->
                val readingStateIndex = statement.getColumnNames().indexOf("reading_state")
                val states =
                    buildList {
                        while (statement.step()) add(statement.getText(readingStateIndex))
                    }
                assertEquals(listOf("completed", "reading", "to_read"), states)
            }
    }

    @Test
    fun migrate22To23_addsUpdatedAtColumnAndPreservesRows() {
        // Seed a v22 database with a book + a legacy reading_sessions row.
        helper.createDatabase(22).apply {
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
                23,
                listOf(AppDatabaseMigrations.MIGRATION_22_23),
            )

        // R1: the LWW column exists after migration.
        assertEquals(true, db.columnNames("reading_sessions").contains("updated_at_epoch_millis"))

        // R2: existing rows are preserved with the default clock (0).
        db.prepare("SELECT id, updated_at_epoch_millis FROM reading_sessions").use { statement ->
            check(statement.step()) { "Expected migrated reading_sessions row to exist" }
            val idIndex = statement.getColumnNames().indexOf("id")
            val clockIndex = statement.getColumnNames().indexOf("updated_at_epoch_millis")
            assertEquals("sess-1", statement.getText(idIndex))
            assertEquals(0L, statement.getLong(clockIndex))
        }

        db.close()
    }

    @Test
    fun migrate23To24_addsMetadataColumnsAndDoublesRatings() {
        // Seed a v23 database with the exact books schema exported in 23.json,
        // plus rows covering rated (1..5) and unrated (NULL) books.
        helper.createDatabase(23).apply {
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
                24,
                listOf(AppDatabaseMigrations.MIGRATION_23_24),
            )

        // R1: the 5 new metadata columns exist and are nullable.
        val info = db.tableInfo("books")
        val expected =
            mapOf(
                "genre" to false,
                "language" to false,
                "publisher" to false,
                "tags" to false,
                "published_date" to false,
            )
        val actual = expected.keys.associateWith { info[it] == 1 }
        assertEquals(expected.keys, actual.keys)
        assertEquals(expected.values, expected.keys.map { actual[it] })

        // R2: ratings reinterpreted as half-units (1→2, 3→6, 5→10); NULL stays NULL.
        db.prepare("SELECT id, user_rating FROM books ORDER BY id").use { statement ->
            val idIndex = statement.getColumnNames().indexOf("id")
            val ratingIndex = statement.getColumnNames().indexOf("user_rating")
            val ratings =
                buildList {
                    while (statement.step()) {
                        add(
                            statement.getText(idIndex) to
                                if (statement.isNull(ratingIndex)) null else statement.getLong(ratingIndex),
                        )
                    }
                }
            assertEquals(listOf("r0" to null, "r1" to 2L, "r3" to 6L, "r5" to 10L), ratings)
        }

        // R3: no row loss across the migration.
        assertEquals(4L, db.count("SELECT COUNT(*) FROM books"))

        db.close()
    }
}

package com.nexo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {
    // No-op migrations for historically missing migration paths
    // These prevent fallbackToDestructiveMigration() on clean installs from old backups
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: schema unchanged between v1 and v2
            }
        }

    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: schema unchanged between v2 and v3
            }
        }

    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: schema unchanged between v3 and v4
            }
        }

    val MIGRATION_15_16 =
        object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── Phase 1: Recreate reading_stats (FK + column rename bookId→book_id) ──
                db.execSQL("PRAGMA foreign_key_checks = OFF")

                // Row count verification
                val readingStatsCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM reading_stats",
                        ).simpleQueryForLong()

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_stats_new (
                        book_id TEXT NOT NULL PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,
                        totalMinutesRead INTEGER NOT NULL DEFAULT 0,
                        lastReadDateEpochMillis INTEGER NOT NULL DEFAULT 0,
                        sessionsCount INTEGER NOT NULL DEFAULT 0,
                        userId TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO reading_stats_new (book_id, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, userId)
                    SELECT bookId, totalMinutesRead, lastReadDateEpochMillis, sessionsCount, userId FROM reading_stats
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE reading_stats")
                db.execSQL("ALTER TABLE reading_stats_new RENAME TO reading_stats")

                val newReadingStatsCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM reading_stats",
                        ).simpleQueryForLong()
                if (newReadingStatsCount != readingStatsCount) {
                    throw IllegalStateException(
                        "reading_stats row count mismatch: before=$readingStatsCount after=$newReadingStatsCount",
                    )
                }

                // ── Phase 2: Recreate sync_file_mappings (add FK) ──
                val syncFileMappingCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM sync_file_mappings",
                        ).simpleQueryForLong()

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_file_mappings_new (
                        drive_file_id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                        local_path TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO sync_file_mappings_new SELECT * FROM sync_file_mappings")
                db.execSQL("DROP TABLE sync_file_mappings")
                db.execSQL("ALTER TABLE sync_file_mappings_new RENAME TO sync_file_mappings")

                val newSyncFileMappingCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM sync_file_mappings",
                        ).simpleQueryForLong()
                if (newSyncFileMappingCount != syncFileMappingCount) {
                    throw IllegalStateException(
                        "sync_file_mappings row count mismatch: before=$syncFileMappingCount after=$newSyncFileMappingCount",
                    )
                }

                // ── Phase 3: Recreate sync_outbox (add FK with SET NULL) ──
                val syncOutboxCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM sync_outbox",
                        ).simpleQueryForLong()

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT REFERENCES books(id) ON DELETE SET NULL,
                        operation TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO sync_outbox_new SELECT * FROM sync_outbox")
                db.execSQL("DROP TABLE sync_outbox")
                db.execSQL("ALTER TABLE sync_outbox_new RENAME TO sync_outbox")

                val newSyncOutboxCount =
                    db
                        .compileStatement(
                            "SELECT COUNT(*) FROM sync_outbox",
                        ).simpleQueryForLong()
                if (newSyncOutboxCount != syncOutboxCount) {
                    throw IllegalStateException(
                        "sync_outbox row count mismatch: before=$syncOutboxCount after=$newSyncOutboxCount",
                    )
                }

                // ── Phase 4: Composite indexes ──
                db.execSQL("DROP INDEX IF EXISTS index_highlights_book_id")
                db.execSQL("DROP INDEX IF EXISTS index_bookmarks_book_id")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_deleted_at_updated_at ON books(deleted_at, updated_at DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_book_id_deleted_at ON highlights(book_id, deleted_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_book_id_deleted_at ON bookmarks(book_id, deleted_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_date_userId ON reading_sessions(date, userId)")

                // ── Phase 5: FTS5 virtual table + triggers for dictionary_words ──
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS dictionary_words_fts USING fts5(
                        word, definition, content=dictionary_words, content_rowid=rowid
                    )
                    """.trimIndent(),
                )

                db.execSQL("DROP TRIGGER IF EXISTS dict_ai")
                db.execSQL("DROP TRIGGER IF EXISTS dict_ad")
                db.execSQL("DROP TRIGGER IF EXISTS dict_au")

                db.execSQL(
                    """
                    CREATE TRIGGER dict_ai AFTER INSERT ON dictionary_words BEGIN
                        INSERT INTO dictionary_words_fts(rowid, word, definition)
                        VALUES (new.rowid, new.word, new.definition);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER dict_ad AFTER DELETE ON dictionary_words BEGIN
                        INSERT INTO dictionary_words_fts(dictionary_words_fts, rowid, word, definition)
                        VALUES ('delete', old.rowid, old.word, old.definition);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER dict_au AFTER UPDATE ON dictionary_words BEGIN
                        INSERT INTO dictionary_words_fts(dictionary_words_fts, rowid, word, definition)
                        VALUES ('delete', old.rowid, old.word, old.definition);
                        INSERT INTO dictionary_words_fts(rowid, word, definition)
                        VALUES (new.rowid, new.word, new.definition);
                    END
                    """.trimIndent(),
                )

                db.execSQL("PRAGMA foreign_key_checks = ON")
            }
        }

    val MIGRATION_16_17 =
        object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN content_hash TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_17_18 =
        object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN reading_state TEXT NOT NULL DEFAULT 'to_read'")
                db.execSQL("ALTER TABLE books ADD COLUMN started_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN completed_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN progress_percentage REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN progress_updated_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN state_version INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE books SET reading_state = CASE WHEN status = 'completed' THEN 'completed' WHEN status = 'reading' THEN 'reading' ELSE 'to_read' END")
            }
        }

    val MIGRATION_18_19 =
        object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN remote_file_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN remote_path TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN remote_lifecycle TEXT NOT NULL DEFAULT 'imported'")
                db.execSQL("ALTER TABLE books ADD COLUMN remote_catalog_version INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN remote_cover_ref TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_19_20 =
        object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN remote_provider TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN remote_protocol_version INTEGER DEFAULT NULL")
            }
        }

    val MIGRATION_20_21 =
        object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_entity_id ON sync_outbox(entity_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_file_mappings_book_id ON sync_file_mappings(book_id)")
            }
        }

    /**
     * Orphan cleanup: reading_progress/bookmarks/highlights/reading_stats can
     * reference a book_id that no longer exists in books (e.g. the local copy
     * was removed while cloud progress remained, or a stale sync mapping).
     * Those rows violate the FK and crash the app at startup when remote
     * progress is applied (SQLiteConstraintException). This migration deletes
     * the orphans so a corrupt DB self-heals without requiring reinstall.
     */
    val MIGRATION_21_22 =
        object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM reading_progress WHERE book_id NOT IN (SELECT id FROM books)")
                db.execSQL("DELETE FROM bookmarks WHERE book_id NOT IN (SELECT id FROM books)")
                db.execSQL("DELETE FROM highlights WHERE book_id NOT IN (SELECT id FROM books)")
                db.execSQL("DELETE FROM reading_stats WHERE book_id NOT IN (SELECT id FROM books)")
                db.execSQL("DELETE FROM reading_sessions WHERE book_id NOT IN (SELECT id FROM books)")
                // Sync outbox references may point to a removed book — drop those too.
                db.execSQL("DELETE FROM sync_outbox WHERE entity_id NOT IN (SELECT id FROM books) AND entity_type IN ('READING_PROGRESS','BOOKMARK','HIGHLIGHT')")
            }
        }

    /**
     * Additive LWW write clock for reading_sessions (REQ-reading-sessions-sync-2).
     * `updated_at_epoch_millis` is the local write clock compared against the
     * remote `updated_at` during sync; `start_time` is immutable so it can't
     * serve as the clock. Additive, no data loss.
     */
    val MIGRATION_22_23 =
        object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN updated_at_epoch_millis INTEGER NOT NULL DEFAULT 0")
            }
        }

    /**
     * Book metadata extension (REQ-data-model-1/3): add the 5 new metadata
     * columns (genre/language/publisher/tags/published_date) and reinterpret
     * `user_rating` as half-units 0..10 (D1: 10=5.0 … 1=0.5, null=unrated).
     * The ×2 UPDATE runs exactly once because this is a single-step migration
     * (23 → 24). Additive; no destructive fallback.
     */
    val MIGRATION_23_24 =
        object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN genre TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN language TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN publisher TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN published_date TEXT DEFAULT NULL")
                db.execSQL("UPDATE books SET user_rating = user_rating * 2 WHERE user_rating IS NOT NULL")
            }
        }

    val MIGRATION_14_15 =
        object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN status TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN definition TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN tag TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_stats ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                CREATE TABLE IF NOT EXISTS dictionary_words (
                    id TEXT PRIMARY KEY NOT NULL,
                    word TEXT NOT NULL,
                    addedAtEpochMillis INTEGER NOT NULL
                )
            """,
                )
            }
        }

    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_file_mappings RENAME COLUMN remote_path TO drive_file_id")
            }
        }

    val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN type TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN chapter_count INTEGER DEFAULT NULL")
            }
        }

    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN deleted_at INTEGER")
            }
        }

    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN total_pages INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN user_rating INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN current_page INTEGER DEFAULT NULL")
            }
        }

    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN locator_json TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN locator_json TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN locator_json TEXT DEFAULT NULL")
            }
        }

    val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT DEFAULT NULL")
            }
        }

    /**
     * PR4 — Drop FK on sync_outbox.entity_id (breaks coalescing & offline queue after book delete)
     * and add composite index (entity_type, entity_id) for LWW coalescing lookup.
     * Previous FK SET NULL caused outbox loss when book deleted; cold backup path is FK-ordered
     * at Supabase level, local outbox must survive book deletion (payload carries bookId).
     */
    val MIGRATION_24_25 =
        object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                val count = db.compileStatement("SELECT COUNT(*) FROM sync_outbox").simpleQueryForLong()
                db.execSQL(
                    """
                    CREATE TABLE sync_outbox_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT,
                        operation TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO sync_outbox_new (id, entity_type, entity_id, operation, payload, created_at, retry_count, last_error) SELECT id, entity_type, entity_id, operation, payload, created_at, retry_count, last_error FROM sync_outbox")
                db.execSQL("DROP TABLE sync_outbox")
                db.execSQL("ALTER TABLE sync_outbox_new RENAME TO sync_outbox")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_entity_id ON sync_outbox(entity_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_entity_type_entity_id ON sync_outbox(entity_type, entity_id)")
                val newCount = db.compileStatement("SELECT COUNT(*) FROM sync_outbox").simpleQueryForLong()
                if (newCount != count) throw IllegalStateException("sync_outbox row count mismatch after FK removal: before=$count after=$newCount")
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

    /**
     * Discover catalog cache (PR3): isolated `discover_cache` table keyed by
     * query+page+provider (24h pages) and id+provider (7d details).
     * Additive only — user_books/outbox untouched.
     */
    /**
     * Addon registry (PR1): isolated installed_addons table keyed by
     * sha256(url)[0..16]. Mirrors desktop migration 0017. Additive only.
     */
    val MIGRATION_26_27 =
        object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS installed_addons (
                        id TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        manifest_json TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        added_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_installed_addons_url ON installed_addons(url)",
                )
            }
        }

    val MIGRATION_25_26 =
        object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS discover_cache (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        payload TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL,
                        ttl_s INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_discover_cache_fetched_at ON discover_cache(fetched_at)")
            }
        }

    /**
     * Dictionary rich entries (REQ-DRE-011, REQ-DRE-013): nine additive nullable
     * evidence columns on dictionary_words, mirroring desktop migration 0019.
     * `definition` already exists on Android, so nine of the ten shared evidence
     * columns are added here. No default, no backfill. Declared above `ALL` so
     * Kotlin initialises it before `ALL` hard-references it.
     */
    val MIGRATION_27_28 =
        object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN part_of_speech TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN phonetic TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN example TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN quote TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN source_book_id TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN source_book_title TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN source_book_author TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN source_chapter TEXT")
                db.execSQL("ALTER TABLE dictionary_words ADD COLUMN source_locator TEXT")
            }
        }

    val ALL =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
        )
}

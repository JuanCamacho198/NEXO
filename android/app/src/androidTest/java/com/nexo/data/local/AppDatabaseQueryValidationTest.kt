package com.nexo.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.repository.DictionaryRepositoryImpl
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that the v15→v16 Room refactor produced correct, performant queries.
 *
 * Strategy:
 * - Fresh v16: in-memory DB + RoomDatabase.Callback to create FTS5 + triggers (R1-R4, R7-R12)
 * - Migrated DB: MigrationTestHelper runs MIGRATION_15_16 on a seeded v15 schema (R13)
 * - Rich entry (v28): the ten evidence columns exist as nullable TEXT and round-trip through the
 *   entity, and the reader's evidence-free save path stores NULL evidence (rich-entry 5D.2, 5D.4)
 *
 * Index tests use `EXPLAIN QUERY PLAN` substring matching on `USING INDEX <name>`.
 * This is robust to SQLite version variations in plan formatting.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseQueryValidationTest {
    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    private lateinit var db: AppDatabase

    /**
     * The ten shared evidence columns named by REQ-DRE-001 / REQ-DRE-011. Android already had
     * `definition`, so it gains nine of them; the parity set is the same ten names on all stores.
     */
    private val evidenceColumns =
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

    @Before
    fun setUp() {
        db = createInMemoryDb()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }
    }

    // ────────────────────────── Phase 1: Helpers ──────────────────────────

    /**
     * Create a fresh v16 in-memory AppDatabase.
     * FTS5 virtual table + triggers are created in the RoomDatabase.Callback
     * because the FTS5 content= link + triggers are not part of the Room schema.
     */
    private fun createInMemoryDb(): AppDatabase =
        Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java,
            ).allowMainThreadQueries()
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(connection: SupportSQLiteDatabase) {
                        super.onCreate(connection)
                        connection.execSQL(
                            """
                            CREATE VIRTUAL TABLE IF NOT EXISTS dictionary_words_fts USING fts5(
                                word, definition, content=dictionary_words, content_rowid=rowid
                            )
                            """.trimIndent(),
                        )
                        connection.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS dict_ai AFTER INSERT ON dictionary_words BEGIN
                                INSERT INTO dictionary_words_fts(rowid, word, definition)
                                VALUES (new.rowid, new.word, new.definition);
                            END
                            """.trimIndent(),
                        )
                        connection.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS dict_ad AFTER DELETE ON dictionary_words BEGIN
                                INSERT INTO dictionary_words_fts(dictionary_words_fts, rowid, word, definition)
                                VALUES ('delete', old.rowid, old.word, old.definition);
                            END
                            """.trimIndent(),
                        )
                        connection.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS dict_au AFTER UPDATE ON dictionary_words BEGIN
                                INSERT INTO dictionary_words_fts(dictionary_words_fts, rowid, word, definition)
                                VALUES ('delete', old.rowid, old.word, old.definition);
                                INSERT INTO dictionary_words_fts(rowid, word, definition)
                                VALUES (new.rowid, new.word, new.definition);
                            END
                            """.trimIndent(),
                        )
                    }
                },
            ).build()

    /**
     * Asserts that EXPLAIN QUERY PLAN for [query] uses [expectedIndex] in its detail column.
     * Uses substring match on `USING INDEX <expectedIndex>` to be robust to formatting variations.
     */
    private fun assertUsesIndex(
        query: String,
        expectedIndex: String,
    ) {
        val details = mutableListOf<String>()
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $query").use { cursor ->
            val detailCol = cursor.getColumnIndex("detail")
            check(detailCol >= 0) { "EXPLAIN QUERY PLAN did not return a 'detail' column" }
            while (cursor.moveToNext()) {
                details += cursor.getString(detailCol)
            }
        }
        val planOutput = details.joinToString("\n")
        val needle = "USING INDEX $expectedIndex"
        assertTrue(
            "Expected EXPLAIN QUERY PLAN to contain '$needle' for query:\n  $query\n" +
                "but got plan:\n$planOutput",
            details.any { it.contains(needle) },
        )
    }

    // ────────────────────── Phase 2: Composite Indexes ──────────────────────

    @Test
    fun explainQueryPlan_books_usesIndex() =
        runTest {
            // Seed 2 books with distinct updated_at timestamps
            db.bookDao().upsert(
                BookEntity(
                    id = "b-1",
                    title = "B1",
                    author = "A1",
                    coverPath = null,
                    filePath = "/b1.epub",
                    format = "epub",
                    updatedAtEpochMillis = 100L,
                ),
            )
            db.bookDao().upsert(
                BookEntity(
                    id = "b-2",
                    title = "B2",
                    author = "A2",
                    coverPath = null,
                    filePath = "/b2.epub",
                    format = "epub",
                    updatedAtEpochMillis = 200L,
                ),
            )

            // R1: observeAllBooks query
            assertUsesIndex(
                query = "SELECT * FROM books WHERE deleted_at IS NULL ORDER BY updated_at DESC",
                expectedIndex = "index_books_deleted_at_updated_at",
            )
        }

    @Test
    fun explainQueryPlan_highlights_usesIndex() =
        runTest {
            // Seed 3 highlights for the same book with distinct updated_at
            val bookId = "b-h"
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    title = "HB",
                    author = null,
                    coverPath = null,
                    filePath = "/hb.epub",
                    format = "epub",
                    updatedAtEpochMillis = 100L,
                ),
            )
            for (i in 1..3) {
                db.highlightDao().upsert(
                    HighlightEntity(
                        id = "h-$i",
                        bookId = bookId,
                        cfiRange = "cfi-$i",
                        textContent = "highlight $i",
                        note = null,
                        color = "yellow",
                        updatedAtEpochMillis = i.toLong() * 100L,
                        deletedAtEpochMillis = null,
                    ),
                )
            }

            // R2: observeAllHighlights
            assertUsesIndex(
                query = "SELECT * FROM highlights WHERE deleted_at IS NULL ORDER BY updated_at DESC",
                expectedIndex = "index_highlights_book_id_deleted_at",
            )

            // R3: observeHighlightsForBook (per-book query)
            assertUsesIndex(
                query =
                    "SELECT * FROM highlights WHERE book_id = '$bookId' " +
                        "AND deleted_at IS NULL ORDER BY updated_at DESC",
                expectedIndex = "index_highlights_book_id_deleted_at",
            )
        }

    @Test
    fun explainQueryPlan_bookmarks_usesIndex() =
        runTest {
            val bookId = "b-bm"
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    title = "BMB",
                    author = null,
                    coverPath = null,
                    filePath = "/bmb.epub",
                    format = "epub",
                    updatedAtEpochMillis = 100L,
                ),
            )
            for (i in 1..3) {
                db.bookmarkDao().upsert(
                    BookmarkEntity(
                        id = "bm-$i",
                        bookId = bookId,
                        cfiLocation = "cfi-$i",
                        titleOrSnippet = "bookmark $i",
                        updatedAtEpochMillis = i.toLong() * 100L,
                        deletedAtEpochMillis = null,
                    ),
                )
            }

            // R4: observeAllBookmarks
            assertUsesIndex(
                query = "SELECT * FROM bookmarks WHERE deleted_at IS NULL ORDER BY updated_at DESC",
                expectedIndex = "index_bookmarks_book_id_deleted_at",
            )

            // R5: observeBookmarksForBook
            assertUsesIndex(
                query =
                    "SELECT * FROM bookmarks WHERE book_id = '$bookId' " +
                        "AND deleted_at IS NULL ORDER BY updated_at DESC",
                expectedIndex = "index_bookmarks_book_id_deleted_at",
            )
        }

    @Test
    fun explainQueryPlan_readingSessions_usesIndex() =
        runTest {
            // Seed 3 reading_sessions with mixed userId (one empty, one specific)
            val bookId = "b-rs"
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    title = "RSB",
                    author = null,
                    coverPath = null,
                    filePath = "/rsb.epub",
                    format = "epub",
                    updatedAtEpochMillis = 100L,
                ),
            )
            val date = 20240101L
            for (i in 1..3) {
                db.readingSessionDao().insert(
                    ReadingSessionEntity(
                        id = "rs-$i",
                        bookId = bookId,
                        startTimeEpochMillis = 500L + i,
                        durationMinutes = 10 + i,
                        date = date,
                        userId = if (i == 1) "" else "user-$i",
                    ),
                )
            }

            // R6: OR-pattern query on date + userId
            assertUsesIndex(
                query =
                    "SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions " +
                        "WHERE date = $date AND (userId = 'user-2' OR userId = '')",
                expectedIndex = "index_reading_sessions_date_userId",
            )
        }

    // ──────────────────────── Phase 3: FTS5 Search ────────────────────────

    @Test
    fun fts5_search_returnsCorrectResults() =
        runTest {
            db.dictionaryWordDao().insert(
                DictionaryWordEntity(
                    id = "dw-1",
                    word = "hello",
                    addedAtEpochMillis = 100L,
                    definition = "a greeting",
                    partOfSpeech = "interjection",
                    phonetic = "/həˈloʊ/",
                    example = "hello there",
                    quote = "The hall was quiet save for a single hello.",
                    sourceBookId = "b-hello",
                    sourceBookTitle = "A Book of Greetings",
                    sourceBookAuthor = "A. Author",
                    sourceChapter = "Chapter 1",
                    sourceLocator = "epubcfi(/6/4!/4/2/2)",
                ),
            )

            // R7a: MATCH on inserted word returns 1
            val helloResults = db.dictionaryWordDao().searchFts("hello")
            assertEquals(1, helloResults.size)
            assertEquals("hello", helloResults[0].word)

            // R7b: MATCH on missing word returns 0
            val missResults = db.dictionaryWordDao().searchFts("xyz")
            assertTrue("Expected no matches for 'xyz' but got ${missResults.size}", missResults.isEmpty())
        }

    @Test
    fun fts5_triggers_syncOnInsert() =
        runTest {
            // R8: insert via DAO, then raw-query the FTS5 table to confirm trigger populated it
            db.dictionaryWordDao().insert(
                DictionaryWordEntity(
                    id = "dw-ins",
                    word = "triggerInsert",
                    addedAtEpochMillis = 200L,
                    definition = "insert trigger test",
                    quote = "A paragraph that mentions triggerInsert inline.",
                    sourceBookId = "b-insert",
                    sourceBookTitle = "Trigger Book",
                    sourceBookAuthor = "T. Author",
                    sourceChapter = "Chapter 2",
                    sourceLocator = "epubcfi(/6/8!/4/2/2)",
                ),
            )

            var found = false
            db.openHelper.writableDatabase
                .query(
                    "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH 'triggerInsert'",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if ("triggerInsert" == cursor.getString(0)) {
                            found = true
                        }
                    }
                }
            assertTrue("dict_ai trigger should populate dictionary_words_fts on INSERT", found)
        }

    @Test
    fun fts5_triggers_syncOnDelete() =
        runTest {
            val wordId = "dw-del"
            val word = "triggerDelete"
            db.dictionaryWordDao().insert(
                DictionaryWordEntity(
                    id = wordId,
                    word = word,
                    addedAtEpochMillis = 300L,
                    definition = "delete trigger test",
                    partOfSpeech = "noun",
                    phonetic = "/ˈtrɪɡər/",
                    example = "delete the row",
                ),
            )

            // Sanity: present in FTS5 after insert
            var presentAfterInsert = false
            db.openHelper.writableDatabase
                .query(
                    "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH '$word'",
                ).use { cursor ->
                    if (cursor.moveToFirst()) presentAfterInsert = true
                }
            assertTrue("Word should be present in FTS5 after insert", presentAfterInsert)

            // R9: delete via DAO, then verify the FTS5 row is gone
            db.dictionaryWordDao().delete(wordId)

            var presentAfterDelete = false
            db.openHelper.writableDatabase
                .query(
                    "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH '$word'",
                ).use { cursor ->
                    if (cursor.moveToFirst()) presentAfterDelete = true
                }
            assertFalse("dict_ad trigger should remove word from FTS5 on DELETE", presentAfterDelete)
        }

    @Test
    fun fts5_triggers_syncOnUpdate() =
        runTest {
            val wordId = "dw-upd"
            db.dictionaryWordDao().insert(
                DictionaryWordEntity(
                    id = wordId,
                    word = "oldword",
                    addedAtEpochMillis = 400L,
                    definition = "old def",
                    partOfSpeech = "noun",
                    phonetic = "/oʊld/",
                    example = "an old example",
                ),
            )

            // R10: update word column, assert old value gone, new value present
            db.openHelper.writableDatabase.execSQL(
                "UPDATE dictionary_words SET word = 'newword' WHERE id = '$wordId'",
            )

            var oldFound = false
            db.openHelper.writableDatabase
                .query(
                    "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH 'oldword'",
                ).use { cursor ->
                    if (cursor.moveToFirst()) oldFound = true
                }
            assertFalse("dict_au trigger should remove old value from FTS5 on UPDATE", oldFound)

            var newFound = false
            db.openHelper.writableDatabase
                .query(
                    "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH 'newword'",
                ).use { cursor ->
                    if (cursor.moveToFirst()) newFound = true
                }
            assertTrue("dict_au trigger should add new value to FTS5 on UPDATE", newFound)
        }

    // ────────── Phase 3b: Rich-entry evidence column set (5D.2) ──────────

    /**
     * REQ-DRE-011 / REQ-DRE-010: the Android table exposes the same ten evidence columns as desktop
     * SQLite and Supabase, each a nullable `TEXT` column. Column order, table shape and the
     * local-only columns are deliberately unconstrained — only the named set and its nullability are.
     */
    @Test
    fun dictionaryWords_exposeTheTenEvidenceColumnsAsNullableText() =
        runTest {
            val columns = mutableMapOf<String, Pair<String, Boolean>>()
            db.openHelper.writableDatabase.query("PRAGMA table_info(dictionary_words)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val typeIndex = cursor.getColumnIndex("type")
                val notNullIndex = cursor.getColumnIndex("notnull")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIndex)] =
                        cursor.getString(typeIndex).uppercase() to (cursor.getInt(notNullIndex) != 0)
                }
            }

            evidenceColumns.forEach { column ->
                val info = columns[column]
                assertNotNull("dictionary_words is missing the evidence column $column", info)
                assertEquals("$column should be TEXT", "TEXT", info!!.first)
                assertFalse("$column should be nullable", info.second)
            }
        }

    /** The ten evidence columns must store and return a fully populated row unchanged. */
    @Test
    fun dictionaryWords_roundTripAllTenEvidenceColumns() =
        runTest {
            db.dictionaryWordDao().insert(
                DictionaryWordEntity(
                    id = "dw-rich",
                    word = "efímero",
                    addedAtEpochMillis = 500L,
                    definition = "that lasts a very short time",
                    partOfSpeech = "adjective",
                    phonetic = "/eˈfimeɾo/",
                    example = "Un amor efímero.",
                    quote = "La gloria es efímera.",
                    sourceBookId = "b-odisea",
                    sourceBookTitle = "La Odisea",
                    sourceBookAuthor = "Homero",
                    sourceChapter = "Capítulo III",
                    sourceLocator = "epubcfi(/6/10!/4/2/4)",
                ),
            )

            val stored = db.dictionaryWordDao().findById("dw-rich")
            assertNotNull("the rich row should be readable back", stored)
            assertEquals("that lasts a very short time", stored!!.definition)
            assertEquals("adjective", stored.partOfSpeech)
            assertEquals("/eˈfimeɾo/", stored.phonetic)
            assertEquals("Un amor efímero.", stored.example)
            assertEquals("La gloria es efímera.", stored.quote)
            assertEquals("b-odisea", stored.sourceBookId)
            assertEquals("La Odisea", stored.sourceBookTitle)
            assertEquals("Homero", stored.sourceBookAuthor)
            assertEquals("Capítulo III", stored.sourceChapter)
            assertEquals("epubcfi(/6/10!/4/2/4)", stored.sourceLocator)
        }

    // ─────────────────────── Phase 4: PagingSource ───────────────────────

    @Test
    fun pagingSource_books_returnsCorrectPage() =
        runTest {
            // R11: seed 10 books with distinct updated_at
            val books =
                (1..10).map { i ->
                    BookEntity(
                        id = "pb-$i",
                        title = "Paged Book $i",
                        author = "A$i",
                        coverPath = null,
                        filePath = "/pb$i.epub",
                        format = "epub",
                        updatedAtEpochMillis = i.toLong() * 1000L,
                    )
                }
            db.bookDao().upsertAll(books)

            val pagingSource = db.bookDao().observeAllBooksPaged()

            // Refresh with loadSize=5 — Room's PagingSource interprets null key as start
            val refreshResult =
                pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 5,
                        placeholdersEnabled = false,
                    ),
                )
            assertTrue("Expected LoadResult.Page", refreshResult is PagingSource.LoadResult.Page)
            val firstPage = refreshResult as PagingSource.LoadResult.Page
            assertEquals("First page should contain 5 items", 5, firstPage.data.size)
            assertEquals("nextKey should be 5", 5, firstPage.nextKey)

            // Append with key=5, loadSize=5 — second page should contain the remaining 5
            val appendResult =
                pagingSource.load(
                    PagingSource.LoadParams.Append(
                        key = 5,
                        loadSize = 5,
                        placeholdersEnabled = false,
                    ),
                )
            assertTrue("Expected LoadResult.Page", appendResult is PagingSource.LoadResult.Page)
            val secondPage = appendResult as PagingSource.LoadResult.Page
            assertEquals("Second page should contain 5 items", 5, secondPage.data.size)
            // Room's RoomPagingSource marks nextKey=null when there are no more items
            assertEquals(null, secondPage.nextKey)

            // Items in the two pages must be disjoint and together cover all 10
            val allIds = firstPage.data.map { it.id } + secondPage.data.map { it.id }
            assertEquals("Both pages should cover all 10 books", 10, allIds.size)
            assertEquals("No duplicate ids across pages", 10, allIds.toSet().size)
        }

    @Test
    fun pagingSource_highlights_returnsCorrectPage() =
        runTest {
            // R12: seed 10 highlights for the same book
            val bookId = "b-hp"
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    title = "HPB",
                    author = null,
                    coverPath = null,
                    filePath = "/hpb.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1L,
                ),
            )
            val highlights =
                (1..10).map { i ->
                    HighlightEntity(
                        id = "ph-$i",
                        bookId = bookId,
                        cfiRange = "cfi-$i",
                        textContent = "highlight $i",
                        note = null,
                        color = "yellow",
                        updatedAtEpochMillis = i.toLong() * 1000L,
                        deletedAtEpochMillis = null,
                    )
                }
            db.highlightDao().upsertAll(highlights)

            val pagingSource = db.highlightDao().observeAllHighlightsPaged()

            val refreshResult =
                pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 5,
                        placeholdersEnabled = false,
                    ),
                )
            assertTrue(refreshResult is PagingSource.LoadResult.Page)
            val firstPage = refreshResult as PagingSource.LoadResult.Page
            assertEquals(5, firstPage.data.size)
            assertEquals(5, firstPage.nextKey)

            val appendResult =
                pagingSource.load(
                    PagingSource.LoadParams.Append(
                        key = 5,
                        loadSize = 5,
                        placeholdersEnabled = false,
                    ),
                )
            assertTrue(appendResult is PagingSource.LoadResult.Page)
            val secondPage = appendResult as PagingSource.LoadResult.Page
            assertEquals(5, secondPage.data.size)
            assertEquals(null, secondPage.nextKey)

            val allIds = firstPage.data.map { it.id } + secondPage.data.map { it.id }
            assertEquals(10, allIds.toSet().size)
        }

    // ──────────────────── Phase 5: Migration + Query ─────────────────────

    @Test
    fun migrate15To16_indexPlansOnMigratedData() {
        // R13: run MIGRATION_15_16 from a v15 schema with seed data,
        // then verify index plans on the migrated DB.
        val dbName = "query-validation-migration"

        migrationHelper.createDatabase(dbName, 15).apply {
            // Minimal v15 schema — must include ALL tables the MIGRATION_15_16
            // touches (CREATE INDEX / DROP INDEX / INSERT...SELECT). Tables
            // missing from the seed would cause the migration to fail with
            // "no such table" errors.
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
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO books (id, title, file_path, format, updated_at, deleted_at) " +
                    "VALUES ('mb-1', 'Migrated B1', '/mb1.epub', 'epub', 1000, NULL)",
            )

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS reading_progress (
                    id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    cfi_location TEXT NOT NULL,
                    percentage REAL NOT NULL,
                    current_page INTEGER,
                    updated_at INTEGER NOT NULL,
                    locator_json TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )

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
            execSQL("INSERT INTO reading_stats VALUES ('mb-1', 30, 2000, 2, '')")

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
            execSQL("INSERT INTO sync_file_mappings VALUES ('d-1', 'u1', 'mb-1', '/p', 3000)")

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
            execSQL("INSERT INTO sync_outbox VALUES ('o-1', 'BOOK', 'mb-1', 'UPDATE', '{}', 4000, 0, NULL)")

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
            execSQL("INSERT INTO reading_sessions VALUES ('ms-1', 'mb-1', 5000, 15, 20240101, '')")

            // highlights and bookmarks MUST exist in v15 — MIGRATION_15_16
            // creates composite indexes on these tables, which fail with
            // "no such table" if the seed skips them.
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS highlights (
                    id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    cfi_range TEXT NOT NULL,
                    text_content TEXT NOT NULL,
                    note TEXT,
                    color TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    locator_json TEXT,
                    type TEXT,
                    tag TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    cfi_location TEXT NOT NULL,
                    title_or_snippet TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    locator_json TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )

            // Defensive v15→v28 fixture. `createDatabase(dbName, 15)` materializes the real v15
            // table from 15.json, so this `IF NOT EXISTS` statement is a no-op on device; it is kept
            // in step with the current `DictionaryWordEntity` (the nine rich-entry columns) and the
            // seed INSERT names its columns explicitly so it stays valid either way.
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS dictionary_words (
                    id TEXT NOT NULL,
                    word TEXT NOT NULL,
                    addedAtEpochMillis INTEGER NOT NULL,
                    definition TEXT,
                    part_of_speech TEXT,
                    phonetic TEXT,
                    example TEXT,
                    quote TEXT,
                    source_book_id TEXT,
                    source_book_title TEXT,
                    source_book_author TEXT,
                    source_chapter TEXT,
                    source_locator TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, definition) " +
                    "VALUES ('md-1', 'migrated', 6000, 'post-migration word')",
            )

            close()
        }

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                dbName,
                16,
                true,
                AppDatabaseMigrations.MIGRATION_15_16,
            )

        // Helper local to the test: run EXPLAIN QUERY PLAN on the migrated DB
        fun assertMigratedIndex(
            query: String,
            expectedIndex: String,
        ) {
            val details = mutableListOf<String>()
            migrated.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                val detailCol = cursor.getColumnIndex("detail")
                check(detailCol >= 0) { "EXPLAIN QUERY PLAN did not return a 'detail' column" }
                while (cursor.moveToNext()) {
                    details += cursor.getString(detailCol)
                }
            }
            val needle = "USING INDEX $expectedIndex"
            assertTrue(
                "On migrated v16 DB, expected '$needle' for query:\n  $query\n" +
                    "but got plan:\n${details.joinToString("\n")}",
                details.any { it.contains(needle) },
            )
        }

        // All 4 index plan assertions on the LIVE migrated data
        assertMigratedIndex(
            "SELECT * FROM books WHERE deleted_at IS NULL ORDER BY updated_at DESC",
            "index_books_deleted_at_updated_at",
        )
        assertMigratedIndex(
            "SELECT * FROM highlights WHERE deleted_at IS NULL ORDER BY updated_at DESC",
            "index_highlights_book_id_deleted_at",
        )
        assertMigratedIndex(
            "SELECT * FROM bookmarks WHERE deleted_at IS NULL ORDER BY updated_at DESC",
            "index_bookmarks_book_id_deleted_at",
        )
        assertMigratedIndex(
            "SELECT COALESCE(SUM(duration_minutes), 0) FROM reading_sessions " +
                "WHERE date = 20240101 AND (userId = 'u1' OR userId = '')",
            "index_reading_sessions_date_userId",
        )

        // FTS5 sanity check on migrated data — the trigger only fires on NEW writes,
        // so pre-existing v15 rows are not back-filled into dictionary_words_fts.
        // We INSERT a fresh word post-migration and verify the trigger populates FTS5.
        migrated.execSQL(
            "INSERT INTO dictionary_words (id, word, addedAtEpochMillis, definition) " +
                "VALUES ('md-2', 'postmigration', 7000, 'inserted after migration')",
        )

        var ftsFound = false
        migrated
            .query(
                "SELECT word FROM dictionary_words_fts WHERE dictionary_words_fts MATCH 'postmigration'",
            ).use { cursor ->
                if (cursor.moveToFirst()) ftsFound = true
            }
        assertTrue(
            "After migration, FTS5 triggers should fire on new INSERTs into dictionary_words",
            ftsFound,
        )

        migrated.close()
    }

    // ────────── Phase 7: Android reader no-capture proof (5D.4) ──────────

    /**
     * REQ-DRE-010 scenario 2. Android's only add-to-dictionary entry points —
     * `ShareDictionaryManager.onAddToDictionary` / `onSaveDefinition` — call `save(word[, definition])`
     * and pass no evidence, because Android has no capture surface in this change. A repository save
     * with defaults must therefore persist NULL for every evidence (and user-authored) column and
     * surface no capture error.
     */
    @Test
    fun readerDictionaryPath_persistsNullEvidenceAndSurfacesNoError() =
        runTest {
            val repository = DictionaryRepositoryImpl(db.dictionaryWordDao())

            val result = repository.save("readerword")

            assertTrue("the reader save path must not surface a capture error", result.isSuccess)

            val saved = result.getOrThrow()
            val stored =
                checkNotNull(db.dictionaryWordDao().findById(saved.id)) {
                    "the saved row should be readable back"
                }
            assertNull("quote must stay NULL — Android captures no evidence", stored.quote)
            assertNull(stored.sourceBookId)
            assertNull(stored.sourceBookTitle)
            assertNull(stored.sourceBookAuthor)
            assertNull(stored.sourceChapter)
            assertNull(stored.sourceLocator)
            assertNull(stored.definition)
            assertNull(stored.partOfSpeech)
            assertNull(stored.phonetic)
            assertNull(stored.example)
        }

    // ──────────────── Sanity test (always present, not in spec) ────────────────

    @Test
    fun sanity_dbIsUsable() {
        // Smoke test — if the in-memory DB + callback setup is broken,
        // every other test would fail anyway. Defined first to fail fast.
        assertNotNull(db.bookDao())
        assertNotNull(db.highlightDao())
        assertNotNull(db.bookmarkDao())
        assertNotNull(db.readingSessionDao())
        assertNotNull(db.dictionaryWordDao())
    }
}

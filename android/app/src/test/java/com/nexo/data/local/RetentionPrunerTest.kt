package com.nexo.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.HighlightEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-14: the retention job removes only soft-deleted rows older than the
 * threshold and never touches live or recently-tombstoned rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionPrunerTest {
    private lateinit var db: AppDatabase
    private lateinit var pruner: RetentionPruner

    private val now = 10_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        pruner = RetentionPruner(db, clockMillis = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun count(table: String): Long =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use {
                it.moveToFirst()
                it.getLong(0)
            }

    private fun book(
        id: String,
        deletedAt: Long?,
    ) = BookEntity(
        id = id,
        title = id,
        author = null,
        coverPath = null,
        filePath = "/$id.epub",
        format = "epub",
        updatedAtEpochMillis = now,
        deletedAtEpochMillis = deletedAt,
    )

    @Test
    fun `prunes only old soft-deletes and vacuums`() =
        runBlocking {
            val old = now - RetentionPruner.DEFAULT_THRESHOLD_MILLIS - 1
            val fresh = now - 1_000

            db.bookDao().upsert(book("book-live", null))
            db.bookDao().upsert(book("book-old", old))
            db.bookDao().upsert(book("book-fresh", fresh))

            db.highlightDao().upsert(
                HighlightEntity(
                    id = "h-old",
                    bookId = "book-live",
                    cfiRange = "cfi-old",
                    textContent = "old",
                    note = null,
                    color = "yellow",
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = old,
                ),
            )
            db.highlightDao().upsert(
                HighlightEntity(
                    id = "h-live",
                    bookId = "book-live",
                    cfiRange = "cfi-live",
                    textContent = "live",
                    note = null,
                    color = "yellow",
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = null,
                ),
            )

            db.bookmarkDao().upsert(
                BookmarkEntity(
                    id = "bm-old",
                    bookId = "book-live",
                    cfiLocation = "cfi-old",
                    titleOrSnippet = "old",
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = old,
                ),
            )
            db.bookmarkDao().upsert(
                BookmarkEntity(
                    id = "bm-fresh",
                    bookId = "book-live",
                    cfiLocation = "cfi-fresh",
                    titleOrSnippet = "fresh",
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = fresh,
                ),
            )

            val report = pruner.pruneAndVacuum()

            assertEquals(1, report.books)
            assertEquals(1, report.highlights)
            assertEquals(1, report.bookmarks)
            assertEquals(3, report.total)

            assertEquals(2L, count("books"))
            assertEquals(1L, count("highlights"))
            assertEquals(1L, count("bookmarks"))
        }
}

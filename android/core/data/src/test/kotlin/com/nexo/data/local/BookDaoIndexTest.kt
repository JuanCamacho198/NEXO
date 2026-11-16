package com.nexo.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.entity.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-12: the library `reading_state` filter is served by
 * `index_books_reading_state` (created for fresh installs by the entity index
 * and for upgrades by MIGRATION_30_31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDaoIndexTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun explain(query: String): String {
        val details = mutableListOf<String>()
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $query").use { cursor ->
            val detailIndex = cursor.getColumnIndex("detail")
            while (cursor.moveToNext()) details.add(cursor.getString(detailIndex))
        }
        return details.joinToString("\n")
    }

    @Test
    fun `reading_state filter uses the reading_state index`() =
        runBlocking {
            db.bookDao().upsert(
                BookEntity(
                    id = "b-reading",
                    title = "Reading",
                    author = null,
                    coverPath = null,
                    filePath = "/b.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1L,
                    readingState = "reading",
                ),
            )

            val plan = explain("SELECT * FROM books WHERE reading_state = 'reading'")
            assertTrue(
                "reading_state filter must use index_books_reading_state but plan was:\n$plan",
                plan.contains("index_books_reading_state"),
            )
        }
}

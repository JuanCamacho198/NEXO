package com.nexo.data.local

import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.testutil.FakeBookDao
import com.nexo.testutil.FakeBookmarkDao
import com.nexo.testutil.FakeDictionaryWordDao
import com.nexo.testutil.FakeHighlightDao
import com.nexo.testutil.FakeReadingProgressDao
import com.nexo.testutil.FakeSyncOutboxDao
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the per-account isolation the `user_id` refactor introduced: two
 * accounts seeded into the same store never observe each other's rows.
 *
 * The fakes in `com.nexo.testutil` mirror the DAO SQL predicates
 * (`WHERE user_id = :userId`), so this is the JVM fast-path proof that the
 * scoping contract holds for the tables carrying the most user data.
 */
class UserDataIsolationTest {
    // ─── Books ─────────────────────────────────────────────────────────

    @Test
    fun books_areScopedToTheirOwner() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(book("book-a", TEST_USER_ID))
            dao.upsert(book("book-b", OTHER_USER_ID))

            assertEquals(listOf("book-a"), dao.observeAllBooks(TEST_USER_ID).first().map { it.id })
            assertEquals(listOf("book-b"), dao.observeAllBooks(OTHER_USER_ID).first().map { it.id })

            // A direct id lookup is also owner-filtered.
            assertNull(dao.getBookById("book-b", TEST_USER_ID))
            assertNull(dao.getBookById("book-a", OTHER_USER_ID))

            assertEquals(1, dao.count(TEST_USER_ID))
            assertEquals(1, dao.count(OTHER_USER_ID))
        }

    @Test
    fun books_mutationsNeverTouchAnotherOwnersRow() =
        runBlocking {
            val dao = FakeBookDao()
            dao.upsert(book("book-a", TEST_USER_ID))
            dao.upsert(book("book-b", OTHER_USER_ID))

            // A delete attributed to the wrong account must be a no-op.
            dao.deleteBook("book-b", TEST_USER_ID, deletedAt = 100L)

            assertNull(dao.getBookById("book-b", OTHER_USER_ID)?.deletedAtEpochMillis)
            assertEquals(1, dao.observeAllBooks(OTHER_USER_ID).first().size)
        }

    // ─── Highlights ────────────────────────────────────────────────────

    @Test
    fun highlights_areScopedToTheirOwner() =
        runBlocking {
            val dao = FakeHighlightDao()
            dao.upsert(highlight("h-a", "book-a", TEST_USER_ID))
            dao.upsert(highlight("h-b", "book-b", OTHER_USER_ID))

            assertEquals(listOf("h-a"), dao.observeAllHighlights(TEST_USER_ID).first().map { it.id })
            assertEquals(listOf("h-b"), dao.observeAllHighlights(OTHER_USER_ID).first().map { it.id })

            assertNull(dao.getHighlightById("h-b", TEST_USER_ID))
            assertNull(dao.getHighlightById("h-a", OTHER_USER_ID))

            assertEquals(1, dao.count(TEST_USER_ID))
        }

    @Test
    fun highlightTagsIgnoreAnotherOwnersTags() =
        runBlocking {
            val dao = FakeHighlightDao()
            dao.upsert(highlight("h-a", "book-a", TEST_USER_ID).copy(tag = "mine"))
            dao.upsert(highlight("h-b", "book-b", OTHER_USER_ID).copy(tag = "theirs"))

            assertEquals(listOf("mine"), dao.observeAllTags(TEST_USER_ID).first())
            assertEquals(listOf("theirs"), dao.observeAllTags(OTHER_USER_ID).first())
        }

    // ─── Bookmarks ─────────────────────────────────────────────────────

    @Test
    fun bookmarks_areScopedToTheirOwner() =
        runBlocking {
            val dao = FakeBookmarkDao()
            dao.upsert(bookmark("bm-a", "book-a", TEST_USER_ID))
            dao.upsert(bookmark("bm-b", "book-b", OTHER_USER_ID))

            assertEquals(listOf("bm-a"), dao.observeAllBookmarks(TEST_USER_ID).first().map { it.id })
            assertEquals(listOf("bm-b"), dao.observeAllBookmarks(OTHER_USER_ID).first().map { it.id })

            assertNull(dao.getBookmarkById("bm-b", TEST_USER_ID))
            assertNull(dao.getBookmarkById("bm-a", OTHER_USER_ID))

            assertEquals(1, dao.count(TEST_USER_ID))
        }

    // ─── Reading progress ──────────────────────────────────────────────

    @Test
    fun readingProgress_isScopedToItsOwner() =
        runBlocking {
            val dao = FakeReadingProgressDao()
            dao.upsert(progress("book-a", TEST_USER_ID, percentage = 10f))
            dao.upsert(progress("book-b", OTHER_USER_ID, percentage = 90f))

            assertEquals(10f, dao.getProgressForBook("book-a", TEST_USER_ID)?.percentage)
            assertNull(dao.getProgressForBook("book-b", TEST_USER_ID))

            assertEquals(90f, dao.getProgressForBook("book-b", OTHER_USER_ID)?.percentage)
            assertNull(dao.getProgressForBook("book-a", OTHER_USER_ID))

            assertEquals(1, dao.count(TEST_USER_ID))
            assertEquals(1, dao.count(OTHER_USER_ID))
        }

    // ─── Dictionary ────────────────────────────────────────────────────

    @Test
    fun dictionaryWords_areScopedToTheirOwner() =
        runBlocking {
            val dao = FakeDictionaryWordDao()
            dao.insertRaw(word("w-a", "alfabeto", TEST_USER_ID))
            dao.insertRaw(word("w-b", "betamax", OTHER_USER_ID))

            assertEquals(listOf("alfabeto"), dao.observeAll(TEST_USER_ID).first().map { it.word })
            assertEquals(listOf("betamax"), dao.observeAll(OTHER_USER_ID).first().map { it.word })

            assertNull(dao.findById("w-b", TEST_USER_ID))
            assertNull(dao.findById("w-a", OTHER_USER_ID))
        }

    @Test
    fun dictionarySearchAndExistenceRespectTheOwner() =
        runBlocking {
            val dao = FakeDictionaryWordDao()
            dao.insertRaw(word("w-a", "alfabeto", TEST_USER_ID))
            dao.insertRaw(word("w-b", "betamax", OTHER_USER_ID))

            assertEquals(1, dao.search("alfa", TEST_USER_ID).first().size)
            assertTrue(dao.search("beta", TEST_USER_ID).first().isEmpty())

            assertTrue(dao.existsNormalized("alfabeto", TEST_USER_ID))
            assertFalse(dao.existsNormalized("betamax", TEST_USER_ID))
            assertTrue(dao.existsNormalized("betamax", OTHER_USER_ID))
        }

    // ─── Sync outbox ───────────────────────────────────────────────────

    @Test
    fun outbox_isScopedToItsOwner() =
        runBlocking {
            val dao = FakeSyncOutboxDao()
            dao.insert(outboxItem("o-a", TEST_USER_ID))
            dao.insert(outboxItem("o-b", OTHER_USER_ID))

            assertEquals(listOf("o-a"), dao.getPendingItems(TEST_USER_ID).map { it.id })
            assertEquals(listOf("o-b"), dao.getPendingItems(OTHER_USER_ID).map { it.id })

            assertNull(dao.getByTypeAndEntityId(SyncEntityType.BOOK.name, "book-a", OTHER_USER_ID))
        }

    @Test
    fun outboxRetryAndPruneNeverCrossAccounts() =
        runBlocking {
            val dao = FakeSyncOutboxDao()
            dao.insert(outboxItem("o-a", TEST_USER_ID))
            dao.insert(outboxItem("o-b", OTHER_USER_ID))

            // Retrying under the wrong account must not touch the other owner's row.
            dao.incrementRetryCount("o-b", TEST_USER_ID, "boom")

            assertEquals(0, dao.getPendingItems(OTHER_USER_ID).single().retryCount)
            assertEquals(0, dao.getPendingItems(TEST_USER_ID).single().retryCount)
        }

    // ─── Fixtures ──────────────────────────────────────────────────────

    private fun book(
        id: String,
        userId: String,
    ) = BookEntity(
        id = id,
        title = "Book $id",
        author = "Author",
        coverPath = null,
        filePath = "/$id.epub",
        format = "epub",
        updatedAtEpochMillis = 1L,
        userId = userId,
    )

    private fun highlight(
        id: String,
        bookId: String,
        userId: String,
    ) = HighlightEntity(
        id = id,
        bookId = bookId,
        cfiRange = "epubcfi(/6/4!/4/2)",
        textContent = "text",
        note = null,
        color = "#FACC15",
        updatedAtEpochMillis = 1L,
        deletedAtEpochMillis = null,
        userId = userId,
    )

    private fun bookmark(
        id: String,
        bookId: String,
        userId: String,
    ) = BookmarkEntity(
        id = id,
        bookId = bookId,
        cfiLocation = "epubcfi(/6/4!/4/10)",
        titleOrSnippet = "snippet",
        updatedAtEpochMillis = 1L,
        deletedAtEpochMillis = null,
        userId = userId,
    )

    private fun progress(
        bookId: String,
        userId: String,
        percentage: Float,
    ) = ReadingProgressEntity(
        id = "progress-$bookId",
        bookId = bookId,
        cfiLocation = "epubcfi(/6/4!/4/2)",
        percentage = percentage,
        updatedAtEpochMillis = 1L,
        userId = userId,
    )

    private fun word(
        id: String,
        surface: String,
        userId: String,
    ) = DictionaryWordEntity(
        id = id,
        word = surface,
        addedAtEpochMillis = 1L,
        wordNormalized = surface,
        userId = userId,
    )

    private fun outboxItem(
        id: String,
        userId: String,
    ) = SyncOutboxEntity(
        id = id,
        entityType = SyncEntityType.BOOK.name,
        entityId = "book-$id",
        operation = SyncOperation.UPDATE.name,
        payloadJson = "{}",
        createdAtEpochMillis = 1L,
        userId = userId,
    )
}

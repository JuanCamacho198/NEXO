package com.nexo.data.repository

import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightRect
import com.nexo.testutil.FakeBookDao
import com.nexo.testutil.FakeBookmarkDao
import com.nexo.testutil.FakeHighlightDao
import com.nexo.testutil.FakeReadingProgressDao
import com.nexo.testutil.FakeSyncOutboxDao
import com.nexo.testutil.TEST_USER_ID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-T1 — PDF highlight geometry survives the domain ⇄ entity mapping and the outbox payload,
 * while EPUB rows (and PDF rows written before the columns existed) keep opening with no geometry.
 */
class HighlightGeometryMappingTest {
    private val bookDao = FakeBookDao()
    private val highlightDao = FakeHighlightDao()
    private val outboxDao = FakeSyncOutboxDao()

    private val repository =
        ReaderRepositoryImpl(
            readingProgressDao = FakeReadingProgressDao(),
            highlightDao = highlightDao,
            bookmarkDao = FakeBookmarkDao(),
            bookDao = bookDao,
            outboxDao = outboxDao,
        )

    private fun seedBook() {
        bookDao.books["book-1"] =
            BookEntity(
                id = "book-1",
                title = "Odisea",
                author = null,
                coverPath = null,
                filePath = "/odisea.pdf",
                format = "pdf",
                updatedAtEpochMillis = 1L,
                userId = TEST_USER_ID,
            )
    }

    private fun pdfHighlight(
        id: String = "h-pdf",
        page: Int? = 3,
        rects: List<HighlightRect>? =
            listOf(
                HighlightRect(left = 12.35f, top = 40f, width = 210.76f, height = 14f),
                HighlightRect(left = 30.5f, top = 60.25f, width = 120f, height = 14f),
            ),
    ) = Highlight(
        id = id,
        bookId = "book-1",
        cfiRange = "pdfpage:2",
        textContent = "divinal Odiseo",
        note = null,
        color = "#FACC15",
        updatedAtEpochMillis = 1_000L,
        deletedAtEpochMillis = null,
        page = page,
        rects = rects,
    )

    @Test
    fun upsertPdfHighlight_writesPageAndTwoDecimalRects() =
        runBlocking {
            seedBook()
            repository.upsertHighlight(
                pdfHighlight(
                    rects = listOf(HighlightRect(left = 12.345f, top = 40f, width = 210.756f, height = 14f)),
                ),
                TEST_USER_ID,
            )

            val stored = highlightDao.rows.getValue("h-pdf")
            assertEquals(3, stored.page)
            assertEquals(
                """{"v":1,"rects":[{"left":12.35,"top":40.0,"width":210.76,"height":14.0}]}""",
                stored.rectJson,
            )
        }

    @Test
    fun outboxPayload_carriesPageAndRectJson() =
        runBlocking {
            seedBook()
            repository.upsertHighlight(pdfHighlight(), TEST_USER_ID)

            val payload = JSONObject(outboxDao.items.single().payloadJson)
            assertEquals(3, payload.getInt("page"))
            assertEquals(
                highlightDao.rows.getValue("h-pdf").rectJson,
                payload.getString("rectJson"),
            )
            // cfiRange keeps its own 0-based convention — untouched by geometry.
            assertEquals("pdfpage:2", payload.getString("cfiRange"))
        }

    @Test
    fun storedEntity_mapsBackIntoDomainWithPageAndRects() =
        runBlocking {
            seedBook()
            val original = pdfHighlight()
            repository.upsertHighlight(original, TEST_USER_ID)

            val loaded = repository.getHighlightsForBook("book-1", TEST_USER_ID).single()

            assertEquals(3, loaded.page)
            assertEquals(original.rects, loaded.rects)
        }

    @Test
    fun epubShapedRow_staysWithoutGeometry() =
        runBlocking {
            seedBook()
            highlightDao.upsert(
                HighlightEntity(
                    id = "h-epub",
                    bookId = "book-1",
                    cfiRange = "epubcfi(/6/4!/4/2,/1:10,/1:30)",
                    textContent = "texto",
                    note = null,
                    color = "#4ADE80",
                    updatedAtEpochMillis = 2_000L,
                    deletedAtEpochMillis = null,
                    userId = TEST_USER_ID,
                ),
            )

            val loaded = repository.getHighlightsForBook("book-1", TEST_USER_ID).single()
            assertNull(loaded.page)
            assertNull(loaded.rects)
        }

    @Test
    fun upsertEpubHighlight_writesNullGeometry() =
        runBlocking {
            seedBook()
            repository.upsertHighlight(
                pdfHighlight(id = "h-epub-2").copy(
                    cfiRange = "epubcfi(/6/4!/4/2)",
                    page = null,
                    rects = null,
                ),
                TEST_USER_ID,
            )

            val stored = highlightDao.rows.getValue("h-epub-2")
            assertNull(stored.page)
            assertNull(stored.rectJson)

            val payload = JSONObject(outboxDao.items.single().payloadJson)
            assertTrue("page must be an explicit JSON null", payload.isNull("page"))
            assertTrue("rectJson must be an explicit JSON null", payload.isNull("rectJson"))
        }

    @Test
    fun legacyRectJsonWithoutVersion_isStillReadable() =
        runBlocking {
            seedBook()
            highlightDao.upsert(
                HighlightEntity(
                    id = "h-legacy",
                    bookId = "book-1",
                    cfiRange = "pdfpage:0",
                    textContent = "t",
                    note = null,
                    color = "#FACC15",
                    updatedAtEpochMillis = 3_000L,
                    deletedAtEpochMillis = null,
                    userId = TEST_USER_ID,
                    page = 1,
                    rectJson = """{"rects":[{"left":1,"top":2,"width":3,"height":4}]}""",
                ),
            )

            val loaded = repository.getHighlightsForBook("book-1", TEST_USER_ID).single()
            assertEquals(listOf(HighlightRect(left = 1f, top = 2f, width = 3f, height = 4f)), loaded.rects)
        }
}

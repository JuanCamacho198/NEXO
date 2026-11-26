package com.nexo.data.remote.supabase

import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.session.SessionManager
import com.nexo.data.sync.HighlightRectCodec
import com.nexo.domain.model.AuthSession
import com.nexo.testutil.FakeBookDao
import com.nexo.testutil.FakeBookmarkDao
import com.nexo.testutil.FakeHighlightDao
import com.nexo.testutil.FakeReadingProgressDao
import com.nexo.testutil.FakeReadingSessionDao
import com.nexo.testutil.FakeSyncOutboxDao
import com.nexo.testutil.TEST_USER_ID
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * P3-T2 — the approved highlight geometry crosses the Supabase boundary in both directions.
 *
 * Column contract: `page` (1-based, null for EPUB) and `rect_json`
 * (`{"v":1,"rects":[…]}`). Reads must survive `rect_json = null` (EPUB and legacy PDF rows) and
 * any key a newer client adds.
 */
class HighlightGeometrySyncTest {
    private val fakeBookDao = FakeBookDao()
    private val fakeHighlightDao = FakeHighlightDao()
    private val fakeOutboxDao = FakeSyncOutboxDao()
    private lateinit var mockSessionManager: SessionManager
    private lateinit var mockDataSource: SupabaseProgressDataSource
    private lateinit var sync: SupabaseProgressSync
    private val rowSlot: CapturingSlot<HighlightRow> = slot()

    private val rectsJson = """{"v":1,"rects":[{"left":12.35,"top":40.0,"width":210.76,"height":14.0}]}"""

    @Before
    fun setUp() {
        mockSessionManager = mockk(relaxed = true)
        mockDataSource = mockk(relaxed = true)
        coEvery { mockSessionManager.ensureFreshSession() } returns
            Result.success(AuthSession(userId = TEST_USER_ID, email = "test@example.com"))
        coEvery { mockSessionManager.getCurrentSession() } returns
            Result.success(AuthSession(userId = TEST_USER_ID, email = "test@example.com"))

        fakeBookDao.books["book-1"] =
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

        sync =
            SupabaseProgressSync(
                outboxDao = fakeOutboxDao,
                bookDao = fakeBookDao,
                readingProgressDao = FakeReadingProgressDao(),
                bookmarkDao = FakeBookmarkDao(),
                highlightDao = fakeHighlightDao,
                readingSessionDao = FakeReadingSessionDao(),
                sessionManager = mockSessionManager,
                dataSource = mockDataSource,
            )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    private fun localHighlight(
        id: String = "h-pdf",
        page: Int? = 3,
        rectJson: String? = rectsJson,
    ) = HighlightEntity(
        id = id,
        bookId = "book-1",
        cfiRange = "pdfpage:2",
        textContent = "divinal Odiseo",
        note = null,
        color = "#FACC15",
        updatedAtEpochMillis = 1_000L,
        deletedAtEpochMillis = null,
        userId = TEST_USER_ID,
        page = page,
        rectJson = rectJson,
    )

    private fun remoteHighlight(
        id: String = "h-remote",
        page: Int? = 4,
        rectJson: JsonObject? = null,
        deletedAt: String? = null,
        updatedAt: String = "2026-01-01T00:00:05.000Z",
    ) = HighlightRow(
        id = id,
        userId = TEST_USER_ID,
        bookId = "book-1",
        cfiRange = "pdfpage:3",
        textContent = "remote",
        color = "#FACC15",
        page = page,
        rectJson = rectJson,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    private suspend fun queueHighlightUpdate(entity: HighlightEntity) {
        fakeHighlightDao.upsert(entity)
        fakeOutboxDao.insert(
            SyncOutboxEntity(
                id = "outbox-${entity.id}",
                entityType = SyncEntityType.HIGHLIGHT.name,
                entityId = entity.id,
                operation = SyncOperation.UPDATE.name,
                payloadJson = "{}",
                createdAtEpochMillis = 1L,
                userId = TEST_USER_ID,
            ),
        )
    }

    // ─── domain → row ───────────────────────────────────────────────

    @Test
    fun outboxHighlight_sendsRealPageAndRectsInsteadOfHardcodedNull() =
        runBlocking {
            queueHighlightUpdate(localHighlight())

            sync.startProcessing()
            Thread.sleep(500)

            coVerify { mockDataSource.upsertHighlight(capture(rowSlot)) }
            val sent = rowSlot.captured
            assertEquals(3, sent.page)
            assertEquals(
                Json.parseToJsonElement(rectsJson).jsonObject,
                sent.rectJson,
            )
        }

    @Test
    fun outboxEpubHighlight_sendsNullGeometry() =
        runBlocking {
            queueHighlightUpdate(
                localHighlight(id = "h-epub", page = null, rectJson = null)
                    .copy(cfiRange = "epubcfi(/6/4!/4/2,/1:10,/1:30)"),
            )

            sync.startProcessing()
            Thread.sleep(500)

            coVerify { mockDataSource.upsertHighlight(capture(rowSlot)) }
            assertNull(rowSlot.captured.page)
            assertNull(rowSlot.captured.rectJson)
        }

    @Test
    fun rowSerialisesToTheApprovedWireShape() {
        val encoded =
            Json
                .encodeToJsonElement(
                    HighlightRow.serializer(),
                    remoteHighlight(rectJson = HighlightRectCodec.storedToJson(rectsJson)),
                ).jsonObject

        assertEquals(Json.parseToJsonElement("4"), encoded["page"])
        assertEquals(
            Json.parseToJsonElement(rectsJson),
            encoded["rect_json"],
        )
    }

    // ─── row → domain ───────────────────────────────────────────────

    @Test
    fun remoteRow_appliesPageAndRectsLocally() =
        runBlocking {
            coEvery { mockDataSource.listHighlights(TEST_USER_ID, null, true) } returns
                listOf(remoteHighlight(rectJson = HighlightRectCodec.storedToJson(rectsJson)))

            sync.pullAllHighlights()

            val stored = fakeHighlightDao.rows.getValue("h-remote")
            assertEquals(4, stored.page)
            assertEquals(rectsJson, stored.rectJson)
        }

    @Test
    fun remoteRowWithNullRectJson_appliesWithoutGeometry() =
        runBlocking {
            coEvery { mockDataSource.listHighlights(TEST_USER_ID, null, true) } returns
                listOf(remoteHighlight(page = 7, rectJson = null))

            sync.pullAllHighlights()

            val stored = fakeHighlightDao.rows.getValue("h-remote")
            assertEquals("page is an own column and survives without rects", 7, stored.page)
            assertNull(stored.rectJson)
        }

    @Test
    fun remoteRowWithUnknownKeysNormalisesToTheKnownShape() =
        runBlocking {
            val futureShape =
                """
                {"v":9,"rects":[{"left":1,"top":2,"width":3,"height":4,"confidence":0.9}],"future":"x"}
                """.trimIndent()
            coEvery { mockDataSource.listHighlights(TEST_USER_ID, null, true) } returns
                listOf(remoteHighlight(rectJson = Json.parseToJsonElement(futureShape).jsonObject))

            sync.pullAllHighlights()

            val stored = fakeHighlightDao.rows.getValue("h-remote")
            assertEquals(
                """{"v":1,"rects":[{"left":1.0,"top":2.0,"width":3.0,"height":4.0}]}""",
                stored.rectJson,
            )
        }

    @Test
    fun remoteDeletedRow_keepsPageAndRectsAlreadyStored() =
        runBlocking {
            // Soft delete only stamps deleted_at locally; geometry must not be lost.
            fakeHighlightDao.upsert(localHighlight(id = "h-remote"))
            coEvery { mockDataSource.listHighlights(TEST_USER_ID, null, true) } returns
                listOf(remoteHighlight(id = "h-remote", deletedAt = "2026-01-01T00:00:09.000Z"))

            sync.pullAllHighlights()

            val stored = fakeHighlightDao.rows.getValue("h-remote")
            assertEquals(3, stored.page)
            assertEquals(rectsJson, stored.rectJson)
            assertNotNull("the remote tombstone must still be applied", stored.deletedAtEpochMillis)
        }
}

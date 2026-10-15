package com.nexo.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.entity.NotificationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        id: String,
        createdAt: Long,
        readAt: Long? = null,
        i18nParams: Map<String, String>? = null,
        targetKind: String? = null,
        targetId: String? = null,
    ) = NotificationEntity(
        id = id,
        createdAt = createdAt,
        source = "SYNC",
        category = "SYSTEM",
        severity = "INFO",
        interruption = "IN_APP",
        i18nKey = "notifications.event.sync.success",
        i18nParams = i18nParams,
        targetKind = targetKind,
        targetId = targetId,
        readAt = readAt,
        dedupKey = null,
    )

    @Test
    fun `upsert then listNewestFirst returns newest first`() =
        runBlocking {
            dao.upsert(entity("a", 1_000))
            dao.upsert(entity("b", 3_000))
            dao.upsert(entity("c", 2_000))

            assertEquals(listOf("b", "c", "a"), dao.listNewestFirst().map { it.id })
            assertEquals(3, dao.count())
        }

    @Test
    fun `i18nParams and target round-trip through the converters`() =
        runBlocking {
            dao.upsert(
                entity(
                    "with-params",
                    1_000,
                    i18nParams = mapOf("name" to "La Odisea", "detail" to "a=b\\c"),
                    targetKind = "book",
                    targetId = "book-1",
                ),
            )

            val row = dao.getById("with-params")!!
            assertEquals(mapOf("name" to "La Odisea", "detail" to "a=b\\c"), row.i18nParams)
            assertEquals("book", row.targetKind)
            assertEquals("book-1", row.targetId)
        }

    @Test
    fun `markRead updates only the target row`() =
        runBlocking {
            dao.upsert(entity("a", 1_000))
            dao.upsert(entity("b", 2_000))

            dao.markRead("a", 9_000)
            assertEquals(9_000L, dao.getById("a")!!.readAt)
            assertNull(dao.getById("b")!!.readAt)
        }

    @Test
    fun `markAllRead marks every unread row`() =
        runBlocking {
            dao.upsert(entity("a", 1_000))
            dao.upsert(entity("b", 2_000, readAt = 500))

            dao.markAllRead(9_000)
            assertEquals(9_000L, dao.getById("a")!!.readAt)
            assertEquals(500L, dao.getById("b")!!.readAt)
            assertEquals(0, dao.unreadCount())
        }

    @Test
    fun `deleteOlderThan removes only expired rows and returns the count`() =
        runBlocking {
            dao.upsert(entity("old", 1_000))
            dao.upsert(entity("new", 5_000))

            assertEquals(1, dao.deleteOlderThan(2_000))
            assertEquals(listOf("new"), dao.listNewestFirst().map { it.id })
        }

    @Test
    fun `deleteBeyondCount keeps exactly the newest N`() =
        runBlocking {
            (1..5).forEach { dao.upsert(entity("n$it", it.toLong())) }

            assertEquals(2, dao.deleteBeyondCount(3))
            assertEquals(listOf("n5", "n4", "n3"), dao.listNewestFirst().map { it.id })
        }

    @Test
    fun `clear removes everything`() =
        runBlocking {
            dao.upsert(entity("a", 1_000))
            dao.clear()
            assertEquals(0, dao.count())
        }
}

package com.nexo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.AppDatabase
import com.nexo.domain.notification.Category
import com.nexo.domain.notification.Interruption
import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationRetention
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.NotificationSource
import com.nexo.domain.notification.NotificationTarget
import com.nexo.domain.notification.Severity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: NotificationRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = NotificationRepository(db.notificationDao(), clockMillis = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun notification(
        id: String,
        createdAt: Long,
        target: NotificationTarget? = null,
        i18nParams: Map<String, String>? = null,
    ) = Notification(
        id = id,
        createdAt = createdAt,
        source = NotificationSource.LIBRARY,
        category = Category.SYSTEM,
        severity = Severity.SUCCESS,
        interruption = Interruption.IN_APP,
        i18nKey = "notifications.event.library.success",
        i18nParams = i18nParams,
        target = target,
    )

    @Test
    fun `save persists and exposes a chronological capped view`() =
        runBlocking {
            repository.save(notification("b", now - 1000))
            repository.save(notification("a", now - 2000))

            assertEquals(listOf("a", "b"), repository.items.first().map { it.id })
            assertEquals(2, db.notificationDao().count())
        }

    @Test
    fun `save prunes the persisted table by age and count`() =
        runBlocking {
            repository.save(notification("expired", now - NotificationRetention.MAX_AGE_MILLIS - 1))
            repository.save(notification("fresh", now))

            assertEquals(listOf("fresh"), repository.items.first().map { it.id })
            assertEquals(listOf("fresh"), db.notificationDao().listNewestFirst().map { it.id })
        }

    @Test
    fun `load restores persisted history and applies retention`() =
        runBlocking {
            repository.save(notification("one", now - 2000))
            repository.save(notification("two", now - 1000))

            val reloaded = NotificationRepository(db.notificationDao(), clockMillis = { now })
            reloaded.load()

            assertEquals(listOf("one", "two"), reloaded.items.first().map { it.id })
            assertEquals(2, reloaded.unreadCount.first())
        }

    @Test
    fun `view is capped at MAX_NOTIFICATIONS while the table keeps the retention bound`() =
        runBlocking {
            repeat(60) { index -> repository.save(notification("n$index", now - (60 - index))) }

            assertEquals(NotificationRepository.MAX_NOTIFICATIONS, repository.items.first().size)
            assertEquals(60, db.notificationDao().count())
        }

    @Test
    fun `markRead and markAllRead update unread count`() =
        runBlocking {
            repository.save(notification("a", now - 2))
            repository.save(notification("b", now - 1))
            assertEquals(2, repository.unreadCount.first())

            repository.markRead("a", now)
            assertEquals(1, repository.unreadCount.first())

            repository.markAllRead(now)
            assertEquals(0, repository.unreadCount.first())
        }

    @Test
    fun `target round-trips through the flattened representation`() =
        runBlocking {
            repository.save(notification("book", now - 2, target = NotificationTarget.Book("book-1")))
            repository.save(notification("route", now - 1, target = NotificationTarget.Route(NotificationRouteTarget.LIBRARY)))

            val items = repository.items.first().associateBy { it.id }
            assertEquals(NotificationTarget.Book("book-1"), items.getValue("book").target)
            assertEquals(NotificationTarget.Route(NotificationRouteTarget.LIBRARY), items.getValue("route").target)
        }

    @Test
    fun `clear empties both the tray and the table`() =
        runBlocking {
            repository.save(notification("a", now))
            repository.clear()

            assertTrue(repository.items.first().isEmpty())
            assertEquals(0, db.notificationDao().count())
        }

    @Test
    fun `an unreadable persisted target degrades to null without a crash`() =
        runBlocking {
            db.notificationDao().upsert(
                com.nexo.data.local.entity.NotificationEntity(
                    id = "unknown",
                    createdAt = now,
                    source = "SYNC",
                    category = "SYSTEM",
                    severity = "INFO",
                    interruption = "IN_APP",
                    i18nKey = "notifications.event.sync.success",
                    i18nParams = null,
                    targetKind = "book",
                    targetId = null,
                    readAt = null,
                    dedupKey = null,
                ),
            )
            repository.load()
            assertNull(repository.items.first().single().target)
        }
}

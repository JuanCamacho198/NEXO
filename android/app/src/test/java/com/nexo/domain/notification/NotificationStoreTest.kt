package com.nexo.domain.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U7: the store composes policy + history + surfaces into one entry point and
 * supplies every ambient signal the pure policy needs.
 */
class NotificationStoreTest {
    private val toastQueue = mutableListOf<ToastRequest>()
    private val osQueue = mutableListOf<Notification>()

    private val foreground = MutableAppForegroundSignal.create()

    private inner class FakeHistory : NotificationHistory {
        private val _items = MutableStateFlow<List<Notification>>(emptyList())
        override val items: StateFlow<List<Notification>> = _items
        private val _unread = MutableStateFlow(0)
        override val unreadCount: StateFlow<Int> = _unread

        var loadCount = 0
        val saved = mutableListOf<Notification>()

        override suspend fun load() {
            loadCount++
        }

        override suspend fun save(notification: Notification) {
            saved += notification
            _items.value = (_items.value + notification).distinctBy { it.id }
            _unread.value = _items.value.count { it.readAt == null }
        }

        override suspend fun markRead(
            id: String,
            readAt: Long,
        ) = Unit

        override suspend fun markAllRead(readAt: Long) = Unit
    }

    private fun store(
        history: FakeHistory,
        isReading: () -> Boolean = { false },
        preferences: NotificationPreferences = NotificationPreferences.DEFAULT,
        now: () -> Long = { 10_000L },
    ) = NotificationStore(
        history = history,
        surfaces = NotificationSurfaces(ToastSink { toastQueue.add(it) }, OsNotifier { osQueue.add(it) }),
        foreground = foreground,
        isReading = isReading,
        preferencesProvider = { preferences },
        now = now,
    )

    private val importDraft =
        NotifyDraft(
            source = NotificationSource.IMPORT,
            severity = Severity.ERROR,
            i18nKey = "notifications.event.import.error",
            i18nParams = mapOf("p1" to "boom"),
            target = NotificationTarget.Route(NotificationRouteTarget.LIBRARY),
        )

    @Test
    fun `foreground in-app notification is accepted but produces no os`() =
        runTest {
            val history = FakeHistory()
            foreground.set(true)
            val resolution = store(history).emit(importDraft)

            assertTrue(resolution.accepted)
            assertTrue(resolution.delivery.toast)
            assertFalse(resolution.delivery.os)
            assertTrue(osQueue.isEmpty())
            assertEquals(1, history.saved.size)
        }

    @Test
    fun `backgrounded system notification produces an os notification`() =
        runTest {
            val history = FakeHistory()
            foreground.set(false)
            val draft =
                importDraft.copy(
                    interruption = Interruption.SYSTEM,
                    source = NotificationSource.STREAK,
                )
            val resolution = store(history).emit(draft)

            assertTrue(resolution.accepted)
            assertTrue(resolution.delivery.os)
            assertEquals(1, osQueue.size)
        }

    @Test
    fun `a disabled category records in the tray but produces neither surface`() =
        runTest {
            val history = FakeHistory()
            foreground.set(false)
            val draft =
                NotifyDraft(
                    source = NotificationSource.STREAK,
                    severity = Severity.WARNING,
                    i18nKey = "notifications.event.streak.warning",
                    interruption = Interruption.SYSTEM,
                )
            val resolution =
                store(history, preferences = NotificationPreferences.DEFAULT.copy(nudge = false)).emit(draft)

            assertTrue(resolution.accepted)
            assertFalse(resolution.delivery.toast)
            assertFalse(resolution.delivery.os)
            assertTrue(toastQueue.isEmpty())
            assertTrue(osQueue.isEmpty())
            assertEquals(1, history.saved.size)
        }

    @Test
    fun `reading silences both surfaces but records in the tray`() =
        runTest {
            val history = FakeHistory()
            foreground.set(false)
            val draft = importDraft.copy(interruption = Interruption.SYSTEM)
            val resolution = store(history, isReading = { true }).emit(draft)

            assertTrue(resolution.accepted)
            assertFalse(resolution.delivery.toast)
            assertFalse(resolution.delivery.os)
            assertEquals(1, history.saved.size)
        }

    @Test
    fun `the store loads history exactly once across emits`() =
        runTest {
            val history = FakeHistory()
            val store = store(history)
            store.ensureLoaded()
            store.emit(importDraft)
            store.emit(importDraft.copy(id = "second"))

            assertEquals(1, history.loadCount)
        }

    @Test
    fun `a duplicate dedup key is rejected and not stored again`() =
        runTest {
            val history = FakeHistory()
            val store = store(history)
            val draft = importDraft.copy(dedupKey = "import:boom")

            assertTrue(store.emit(draft).accepted)
            val second = store.emit(draft)

            assertFalse(second.accepted)
            assertEquals(PolicyRejection.DUPLICATE, second.reason)
            assertEquals(1, history.saved.size)
        }
}

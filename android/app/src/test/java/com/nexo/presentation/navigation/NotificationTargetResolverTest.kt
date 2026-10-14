package com.nexo.presentation.navigation

import com.nexo.domain.notification.Category
import com.nexo.domain.notification.Interruption
import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.NotificationSource
import com.nexo.domain.notification.NotificationTarget
import com.nexo.domain.notification.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U6: the tray's target resolution is a pure mapping (no navigation, no Android). */
class NotificationTargetResolverTest {
    private fun notification(target: NotificationTarget?) =
        Notification(
            id = "n1",
            createdAt = 1,
            source = NotificationSource.IMPORT,
            category = Category.SYSTEM,
            severity = Severity.SUCCESS,
            interruption = Interruption.SILENT,
            i18nKey = "notifications.event.import.success",
            target = target,
        )

    @Test
    fun `a book target resolves to its id`() {
        val resolved = resolveNotificationTarget(notification(NotificationTarget.Book("book-1")))
        assertEquals(ResolvedNotificationTarget.Book("book-1"), resolved)
    }

    @Test
    fun `a blank book id resolves to null (safe no-op)`() {
        assertNull(resolveNotificationTarget(notification(NotificationTarget.Book("  "))))
    }

    @Test
    fun `a null target resolves to null`() {
        assertNull(resolveNotificationTarget(notification(null)))
    }

    @Test
    fun `a library route resolves to the library root`() {
        val resolved = resolveNotificationTarget(notification(NotificationTarget.Route(NotificationRouteTarget.LIBRARY)))
        assertEquals(ResolvedNotificationTarget.Route(NexoDestination.Library), resolved)
    }

    @Test
    fun `a sync route resolves to settings plus the nested sync route`() {
        val resolved = resolveNotificationTarget(notification(NotificationTarget.Route(NotificationRouteTarget.SYNC)))
        assertEquals(
            ResolvedNotificationTarget.Route(NexoDestination.Settings, NexoDestination.SettingsSync.route),
            resolved,
        )
    }
}

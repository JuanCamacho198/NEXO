package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOTIF-03: exactly-once in-app toast dispatch, honoring the resolution. */
class NotificationSurfacesTest {
    private val notifications = mutableListOf<ToastRequest>()

    private fun surfaces() =
        NotificationSurfaces(
            ToastSink { notifications.add(it) },
        )

    private fun notification(
        id: String = "n1",
        severity: Severity = Severity.INFO,
    ) = Notification(
        id = id,
        createdAt = 1_000,
        source = NotificationSource.SYNC,
        category = Category.SYSTEM,
        severity = severity,
        interruption = Interruption.IN_APP,
        i18nKey = "notifications.event.sync.success",
    )

    private fun resolution(
        accepted: Boolean = true,
        toast: Boolean = true,
    ) = PolicyResolution(
        accepted = accepted,
        reason = null,
        category = Category.SYSTEM,
        interruption = Interruption.IN_APP,
        delivery = DeliveryDecision(tray = true, toast = toast, os = false),
    )

    @Test
    fun `allowed toast fires exactly once`() {
        val surfaces = surfaces()
        assertTrue(surfaces.dispatch(notification(), resolution()))
        assertEquals(1, notifications.size)
    }

    @Test
    fun `second dispatch of the same id fires none`() {
        val surfaces = surfaces()
        surfaces.dispatch(notification(), resolution())
        assertFalse(surfaces.dispatch(notification(), resolution()))
        assertEquals(1, notifications.size)
    }

    @Test
    fun `a resolution that forbids the toast fires none`() {
        val surfaces = surfaces()
        assertFalse(surfaces.dispatch(notification(), resolution(toast = false)))
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `a rejected resolution fires none`() {
        val surfaces = surfaces()
        assertFalse(surfaces.dispatch(notification(), resolution(accepted = false, toast = false)))
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `distinct ids each fire once`() {
        val surfaces = surfaces()
        surfaces.dispatch(notification(id = "a"), resolution())
        surfaces.dispatch(notification(id = "b"), resolution())
        assertEquals(2, notifications.size)
    }

    @Test
    fun `toast carries the key not a resolved string`() {
        val surfaces = surfaces()
        surfaces.dispatch(notification(), resolution())
        assertEquals("notifications.event.sync.success", notifications.first().i18nKey)
    }

    @Test
    fun `severity maps to the toast type`() {
        assertEquals(ToastType.SUCCESS, NotificationSurfaces.toastTypeFor(Severity.SUCCESS))
        assertEquals(ToastType.ERROR, NotificationSurfaces.toastTypeFor(Severity.ERROR))
        assertEquals(ToastType.INFO, NotificationSurfaces.toastTypeFor(Severity.INFO))
        assertEquals(ToastType.INFO, NotificationSurfaces.toastTypeFor(Severity.WARNING))
    }
}

package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule-by-rule tests for [decide]. Each test asserts the exact delivery triple
 * so the policy surface cannot drift silently.
 */
class NotificationPolicyTest {
    private fun input(
        source: NotificationSource = NotificationSource.IMPORT,
        severity: Severity = Severity.SUCCESS,
        dedupKey: String? = null,
        category: Category? = null,
        interruption: Interruption? = null,
        isAppForeground: Boolean = true,
        isReading: Boolean = false,
        now: Long = 0L,
        snapshot: PolicySnapshot = PolicySnapshot(),
        preferences: NotificationPreferences = NotificationPreferences.DEFAULT,
    ) = PolicyInput(
        source = source,
        severity = severity,
        dedupKey = dedupKey,
        category = category,
        interruption = interruption,
        isAppForeground = isAppForeground,
        isReading = isReading,
        now = now,
        snapshot = snapshot,
        preferences = preferences,
    )

    @Test
    fun `R1 accepted notification records tray`() {
        val resolution = decide(input(source = NotificationSource.SYNC, severity = Severity.SUCCESS))
        assertTrue(resolution.accepted)
        assertTrue(resolution.delivery.tray)
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `R2 os fires only for SYSTEM interruption while unfocused`() {
        val foregrounded =
            decide(input(source = NotificationSource.STREAK, severity = Severity.WARNING, isAppForeground = true))
        assertFalse(foregrounded.delivery.os)

        val unfocused =
            decide(input(source = NotificationSource.STREAK, severity = Severity.WARNING, isAppForeground = false))
        assertTrue(unfocused.delivery.os)
        assertEquals(DeliveryDecision(tray = true, toast = true, os = true), unfocused.delivery)
    }

    @Test
    fun `R2 in-app interruption never fires os even when unfocused`() {
        val resolution =
            decide(
                input(
                    source = NotificationSource.IMPORT,
                    severity = Severity.ERROR,
                    isAppForeground = false,
                ),
            )
        assertFalse(resolution.delivery.os)
        assertEquals(DeliveryDecision(tray = true, toast = true, os = false), resolution.delivery)
    }

    @Test
    fun `R3 reading silences toast and os but records tray`() {
        val resolution =
            decide(
                input(
                    source = NotificationSource.STREAK,
                    severity = Severity.WARNING,
                    isAppForeground = false,
                    isReading = true,
                ),
            )
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `R4a duplicate dedupKey is rejected`() {
        val resolution =
            decide(
                input(
                    source = NotificationSource.SYNC,
                    severity = Severity.SUCCESS,
                    dedupKey = "sync-done-1",
                    snapshot = PolicySnapshot(dedupKeys = setOf("sync-done-1")),
                ),
            )
        assertFalse(resolution.accepted)
        assertEquals(PolicyRejection.DUPLICATE, resolution.reason)
        assertEquals(DeliveryDecision(tray = false, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `R4b nudge twice on the same day rejects the second`() {
        val day = 1_700_000_000_000L
        val snapshot = PolicySnapshot(nudges = listOf(NudgeRecord(dedupKey = "streak-nudge", createdAt = day)))

        val second =
            decide(
                input(
                    source = NotificationSource.STREAK,
                    severity = Severity.WARNING,
                    dedupKey = "streak-nudge",
                    now = day + 60_000L,
                    snapshot = snapshot,
                ),
            )
        assertFalse(second.accepted)
        assertEquals(PolicyRejection.NUDGE_DAILY_CAP, second.reason)
        assertEquals(DeliveryDecision(tray = false, toast = false, os = false), second.delivery)
    }

    @Test
    fun `R4b nudge on a different day is accepted`() {
        val day = 1_700_000_000_000L
        val snapshot = PolicySnapshot(nudges = listOf(NudgeRecord(dedupKey = "streak-nudge", createdAt = day)))

        val nextDay =
            decide(
                input(
                    source = NotificationSource.STREAK,
                    severity = Severity.WARNING,
                    dedupKey = "streak-nudge",
                    now = day + 86_400_000L,
                    snapshot = snapshot,
                ),
            )
        assertTrue(nextDay.accepted)
        assertNull(nextDay.reason)
    }

    @Test
    fun `R5 disabled category records tray but produces no toast or os`() {
        val disabledSystem =
            NotificationPreferences(
                system = false,
                nudge = true,
                quietHours = QuietHoursSchedule(false, "22:00", "07:00"),
            )
        val resolution =
            decide(
                input(
                    source = NotificationSource.LIBRARY,
                    severity = Severity.SUCCESS,
                    interruption = Interruption.SYSTEM,
                    isAppForeground = false,
                    preferences = disabledSystem,
                ),
            )
        assertTrue(resolution.accepted)
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `R5 disabled nudge category gates the nudge switch`() {
        val disabledNudge =
            NotificationPreferences(
                system = true,
                nudge = false,
                quietHours = QuietHoursSchedule(false, "22:00", "07:00"),
            )
        val resolution =
            decide(
                input(
                    source = NotificationSource.GOAL,
                    severity = Severity.INFO,
                    isAppForeground = false,
                    preferences = disabledNudge,
                ),
            )
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `R7 quiet hours silence toast and os but record tray`() {
        val quietNow = 1_700_000_000_000L
        // 22:00-07:00 always wraps; make the sample time land inside it.
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = quietNow
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
        calendar.set(java.util.Calendar.MINUTE, 30)
        val at2330 = calendar.timeInMillis

        val quiet =
            NotificationPreferences(
                system = true,
                nudge = true,
                quietHours = QuietHoursSchedule(true, "22:00", "07:00"),
            )
        val resolution =
            decide(
                input(
                    source = NotificationSource.STREAK,
                    severity = Severity.WARNING,
                    isAppForeground = false,
                    now = at2330,
                    preferences = quiet,
                ),
            )
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `rejected resolution never delivers`() {
        val resolution =
            decide(
                input(
                    source = NotificationSource.IMPORT,
                    severity = Severity.ERROR,
                    dedupKey = "dup",
                    snapshot = PolicySnapshot(dedupKeys = setOf("dup")),
                ),
            )
        assertFalse(resolution.accepted)
        assertEquals(DeliveryDecision(tray = false, toast = false, os = false), resolution.delivery)
    }

    @Test
    fun `explicit category and interruption overrides win`() {
        val resolution =
            decide(
                input(
                    source = NotificationSource.LIBRARY,
                    severity = Severity.SUCCESS,
                    category = Category.NUDGE,
                    interruption = Interruption.IN_APP,
                ),
            )
        assertEquals(Category.NUDGE, resolution.category)
        assertEquals(Interruption.IN_APP, resolution.interruption)
    }

    @Test
    fun `silent interruption never toasts`() {
        val resolution = decide(input(source = NotificationSource.UPDATE, severity = Severity.INFO))
        assertEquals(Interruption.SILENT, resolution.interruption)
        assertEquals(DeliveryDecision(tray = true, toast = false, os = false), resolution.delivery)
    }
}

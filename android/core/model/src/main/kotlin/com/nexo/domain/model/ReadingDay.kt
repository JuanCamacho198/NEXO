package com.nexo.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Canonical cross-device reading-day contract (Android side).
 *
 * A reading day is the calendar day the user experienced, derived from an
 * instant plus an EXPLICIT zone. Nothing here reads a device default: the zone
 * is always a parameter. Production wires it once at the DI composition root.
 *
 * The persisted representation is 00:00 UTC of that local calendar day — a
 * date-only value any reader (Android or desktop) recovers without knowing the
 * writer's zone. The same expectations are pinned by the shared fixture at
 * `packages/reading-day-fixtures/reading-day-fixtures.json`, consumed by the
 * Rust test on desktop and the Kotlin test here.
 */
object ReadingDay {
    /** The user's local calendar day for [instant] at an explicit [zone]. */
    fun readingDay(
        instant: Instant,
        zone: ZoneId,
    ): LocalDate = instant.atZone(zone).toLocalDate()

    /** Canonical persisted value for [instant] at an explicit [zone]. */
    fun readingDayStartMillis(
        instant: Instant,
        zone: ZoneId,
    ): Long = dayKey(readingDay(instant, zone))

    /** Canonical persisted value: the local calendar day encoded as 00:00 UTC. */
    fun dayKey(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Canonical day key for "now" at an explicit [zone]. */
    fun todayStartMillis(zone: ZoneId): Long = readingDayStartMillis(Instant.now(), zone)
}

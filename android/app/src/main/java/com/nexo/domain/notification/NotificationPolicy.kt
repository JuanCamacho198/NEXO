package com.nexo.domain.notification

import java.util.Calendar

/** Nudge sources — engagement events the reader should be able to switch off. */
private val NUDGE_SOURCES = setOf(NotificationSource.STREAK, NotificationSource.GOAL)

/** Sources whose notifications are about the reader's engagement, not the app. */
fun defaultCategory(source: NotificationSource): Category =
    if (source in NUDGE_SOURCES) Category.NUDGE else Category.SYSTEM

private val DEFAULT_INTERRUPTION: Map<String, Interruption> =
    mapOf(
        "import:success" to Interruption.SILENT,
        "import:error" to Interruption.IN_APP,
        "sync:success" to Interruption.SILENT,
        "sync:error" to Interruption.IN_APP,
        "addons:success" to Interruption.SILENT,
        "addons:error" to Interruption.IN_APP,
        "update:info" to Interruption.SILENT,
        "library:success" to Interruption.SILENT,
        "streak:warning" to Interruption.SYSTEM,
        "streak:error" to Interruption.SYSTEM,
        "streak:success" to Interruption.SYSTEM,
        "goal:info" to Interruption.SYSTEM,
        "goal:warning" to Interruption.SYSTEM,
        "goal:error" to Interruption.SYSTEM,
        "goal:success" to Interruption.IN_APP,
    )

/**
 * Resolve the default interruption for a draft from the table, falling back by
 * category: nudges default to `system`, system events default to `in-app` on
 * error and `silent` otherwise.
 */
fun defaultInterruption(
    source: NotificationSource,
    severity: Severity,
    category: Category,
): Interruption {
    val key = "${source.name.lowercase()}:${severity.name.lowercase()}"
    DEFAULT_INTERRUPTION[key]?.let { return it }
    if (category == Category.NUDGE) return Interruption.SYSTEM
    return if (severity == Severity.ERROR) Interruption.IN_APP else Interruption.SILENT
}

/** True when both timestamps fall on the same calendar day (local time). */
fun isSameDay(
    a: Long,
    b: Long,
): Boolean {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = a
    val yearA = calendar.get(Calendar.YEAR)
    val dayA = calendar.get(Calendar.DAY_OF_YEAR)
    calendar.timeInMillis = b
    val yearB = calendar.get(Calendar.YEAR)
    val dayB = calendar.get(Calendar.DAY_OF_YEAR)
    return yearA == yearB && dayA == dayB
}

/** Tray + rate-limit state the caller passes in. */
data class PolicySnapshot(
    /** Dedup keys currently present in the tray. */
    val dedupKeys: Set<String> = emptySet(),
    /** Nudge emissions already recorded, for the daily per-key cap. */
    val nudges: List<NudgeRecord> = emptyList(),
)

/** A previously recorded nudge emission. */
data class NudgeRecord(
    val dedupKey: String,
    val createdAt: Long,
)

/** Input draft plus injected state; the clock is [now], focus/reading are passed in. */
data class PolicyInput(
    val source: NotificationSource,
    val severity: Severity,
    val dedupKey: String? = null,
    /** Explicit overrides; when omitted the default table resolves them. */
    val category: Category? = null,
    val interruption: Interruption? = null,
    val isAppForeground: Boolean,
    val isReading: Boolean,
    val now: Long = System.currentTimeMillis(),
    val snapshot: PolicySnapshot = PolicySnapshot(),
    val preferences: NotificationPreferences = NotificationPreferences.DEFAULT,
)

/** The resolved per-surface delivery decision. */
data class DeliveryDecision(
    val tray: Boolean,
    val toast: Boolean,
    val os: Boolean,
)

/** Why a draft was rejected. */
enum class PolicyRejection {
    DUPLICATE,
    NUDGE_DAILY_CAP,
}

data class PolicyResolution(
    val accepted: Boolean,
    val reason: PolicyRejection?,
    val category: Category,
    val interruption: Interruption,
    val delivery: DeliveryDecision,
)

/**
 * The pure delivery policy: given a draft plus a snapshot of current state it
 * resolves the category, interruption and the delivery decision. No side
 * effects; the clock and all ambient state are injected.
 *
 * Rules:
 *  1. Every accepted notification is a tray entry.
 *  2. OS fires only when `interruption == SYSTEM` AND the app is not foregrounded.
 *  3. Total silence while reading: no toast, no OS; the tray still records.
 *  4. Dedup + rate limit: an already-known `dedupKey` is not re-emitted; a
 *     `nudge` is capped at one per `dedupKey` per day.
 *  5. Per-category gate: a disabled category produces no toast and no OS.
 *  7. Quiet hours silence toast + OS on a user schedule; the tray still records.
 */
fun decide(input: PolicyInput): PolicyResolution {
    val now = input.now
    val category = input.category ?: defaultCategory(input.source)
    val interruption =
        input.interruption ?: defaultInterruption(input.source, input.severity, category)

    // Rule 4a: an identical event already known is never re-emitted.
    val isDuplicate = input.dedupKey != null && input.dedupKey in input.snapshot.dedupKeys
    // Rule 4b: a nudge is capped at one emission per dedupKey per day.
    val nudgeDailyCapReached =
        category == Category.NUDGE &&
            input.dedupKey != null &&
            input.snapshot.nudges.any { it.dedupKey == input.dedupKey && isSameDay(it.createdAt, now) }

    // Rejection is decided BEFORE delivery: a rejected draft never delivers.
    if (isDuplicate || nudgeDailyCapReached) {
        return PolicyResolution(
            accepted = false,
            reason = if (isDuplicate) PolicyRejection.DUPLICATE else PolicyRejection.NUDGE_DAILY_CAP,
            category = category,
            interruption = interruption,
            delivery = DeliveryDecision(tray = false, toast = false, os = false),
        )
    }

    // Rule 3 (reading silence) and rule 7 (quiet hours) silence the same
    // surfaces; rule 5 (per-category switch) gates them too.
    val quiet = isQuietHoursActive(input.preferences, now)
    val categoryEnabled =
        if (category == Category.NUDGE) input.preferences.nudge else input.preferences.system
    val toast = interruption != Interruption.SILENT && !input.isReading && !quiet && categoryEnabled
    // Rule 2 (SYSTEM + unfocused) with rules 3, 5 and 7 applied.
    val os =
        interruption == Interruption.SYSTEM &&
            !input.isAppForeground &&
            !input.isReading &&
            !quiet &&
            categoryEnabled

    return PolicyResolution(
        accepted = true,
        reason = null,
        category = category,
        interruption = interruption,
        delivery = DeliveryDecision(tray = true, toast = toast, os = os),
    )
}

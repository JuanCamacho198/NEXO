package com.nexo.domain.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One entry point that composes policy + history + surfaces (U7).
 *
 * Emit sites call [emit] with a [NotifyDraft]; the store supplies the ambient
 * state the pure [decide] policy needs — the foreground signal, reading state,
 * the clock, the current tray (as the dedup snapshot) and the cached
 * preferences — then stores the entry and hands the accepted notification to
 * [NotificationSurfaces] for at-most-once per-surface delivery.
 *
 * Loading discipline: the dedup snapshot is only meaningful after
 * [NotificationHistory.load] has run, so [emit] awaits a one-shot [ensureLoaded]
 * guarded by a [Mutex]. The first emit can therefore never race the initial
 * load and silently re-emit a persisted duplicate — it waits for the load
 * instead. Failures inside the load are swallowed by the repository (it leaves
 * the tray empty), so a broken read degrades to "no dedup history", never a
 * dropped or crashing emit.
 *
 * The store owns no scope: [emit] is a `suspend` function the caller already
 * runs inside one.
 */
class NotificationStore(
    private val history: NotificationHistory,
    private val surfaces: NotificationSurfaces,
    private val foreground: AppForegroundSignal,
    private val isReading: () -> Boolean = { ReadingActivity.isReadingNow },
    private val preferencesProvider: () -> NotificationPreferences = { NotificationPreferences.DEFAULT },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val loadMutex = Mutex()

    @Volatile
    private var loaded = false

    /** Loads persisted history once; concurrent callers await the single load. */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            history.load()
            loaded = true
        }
    }

    /**
     * Runs the delivery policy for [draft] and delivers the accepted
     * notification. Returns the [PolicyResolution] so a caller can react (e.g.
     * skip its own feedback when the store already surfaced the event).
     */
    suspend fun emit(draft: NotifyDraft): PolicyResolution {
        ensureLoaded()
        val timestamp = now()
        val notification = draft.toNotification(timestamp)
        val resolution =
            decide(
                PolicyInput(
                    source = notification.source,
                    severity = notification.severity,
                    dedupKey = notification.dedupKey,
                    category = notification.category,
                    interruption = notification.interruption,
                    isAppForeground = foreground.isForeground.value,
                    isReading = isReading(),
                    now = timestamp,
                    snapshot = snapshot(),
                    preferences = preferencesProvider(),
                ),
            )

        if (!resolution.accepted) return resolution

        val stored =
            notification.copy(
                category = resolution.category,
                interruption = resolution.interruption,
            )
        history.save(stored)
        surfaces.dispatch(stored, resolution)
        return resolution
    }

    /**
     * The policy snapshot taken from the CURRENT tray: dedup keys already
     * present. Nudge history is derived the same way (a nudge is a tray entry
     * with a dedup key), so a re-emission on the same day is capped without a
     * second store.
     */
    private fun snapshot(): PolicySnapshot {
        val items = currentItems()
        return PolicySnapshot(
            dedupKeys = items.mapNotNull { it.dedupKey }.toSet(),
            nudges =
                items
                    .filter { it.category == Category.NUDGE && it.dedupKey != null }
                    .mapNotNull { item ->
                        item.dedupKey?.let { NudgeRecord(it, item.createdAt) }
                    },
        )
    }

    /**
     * A best-effort synchronous read of the tray for the snapshot. The
     * repository mirror is a `MutableStateFlow`, whose value is always current
     * without suspending; a fake that only exposes a cold flow degrades to an
     * empty snapshot (no dedup, which is the safe default).
     */
    private fun currentItems(): List<Notification> =
        (history.items as? kotlinx.coroutines.flow.StateFlow<List<Notification>>)?.value ?: emptyList()

    private fun NotifyDraft.toNotification(timestamp: Long): Notification =
        Notification(
            id = id ?: "$DEFAULT_ID_PREFIX${source.name.lowercase()}:$timestamp",
            createdAt = timestamp,
            source = source,
            category = category ?: defaultCategory(source),
            severity = severity,
            interruption = interruption ?: defaultInterruption(source, severity, category ?: defaultCategory(source)),
            i18nKey = i18nKey,
            i18nParams = i18nParams,
            target = target,
            dedupKey = dedupKey,
        )

    companion object {
        private const val DEFAULT_ID_PREFIX = "nexo:"
    }
}

package com.nexo.presentation.viewmodel.highlights

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SYNCED_DISPLAY_DURATION_MS = 3000L

/**
 * Silent window for [SyncTrigger.Automatic]: a pull that finishes inside it
 * never touches the UI. Above it the indicator appears so a slow network
 * explains itself.
 */
private const val AUTO_SYNC_VISIBLE_AFTER_MS = 600L

/** Minimum gap between two [SyncTrigger.Manual] syncs. Extra presses are dropped. */
private const val MANUAL_SYNC_COOLDOWN_MS = 2500L

sealed interface HighlightsSyncState {
    data object Idle : HighlightsSyncState

    data object Syncing : HighlightsSyncState

    data object Synced : HighlightsSyncState
}

/** Who asked for the pull, which decides whether feedback is immediate. */
enum class SyncTrigger {
    /** Screen entry. Invisible unless the pull is slow. */
    Automatic,

    /** Refresh button and pull-to-refresh. The user asked, so show it at once. */
    Manual,
}

/**
 * Owns the Highlights sync state machine.
 *
 * [pull] is the remote fetch (null when sync is unconfigured, which makes the
 * holder a no-op). It is a lambda rather than the sync dependency so the
 * trigger, cooldown and visibility rules can be exercised without a live
 * Supabase client.
 */
class SyncStateHolder(
    private val pull: (suspend () -> Unit)?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _syncState = MutableStateFlow<HighlightsSyncState>(HighlightsSyncState.Idle)
    val syncState: StateFlow<HighlightsSyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var syncedResetJob: Job? = null
    private var lastManualSyncStart = 0L
    private var hasManualSynced = false

    fun syncHighlights(trigger: SyncTrigger = SyncTrigger.Automatic) {
        val pull = pull ?: return

        // A pull already in flight is already fetching the newest data. A new
        // request is ignored rather than cancelling and restarting it.
        if (syncJob?.isActive == true) return

        if (trigger == SyncTrigger.Manual) {
            val startedAt = now()
            if (hasManualSynced && startedAt - lastManualSyncStart < MANUAL_SYNC_COOLDOWN_MS) return
            hasManualSynced = true
            lastManualSyncStart = startedAt
        }

        syncedResetJob?.cancel()
        syncJob =
            scope.launch {
                if (trigger == SyncTrigger.Manual) _syncState.value = HighlightsSyncState.Syncing
                try {
                    var revealed = false
                    coroutineScope {
                        if (trigger == SyncTrigger.Automatic) {
                            val slowReveal =
                                launch {
                                    delay(AUTO_SYNC_VISIBLE_AFTER_MS)
                                    revealed = true
                                    _syncState.value = HighlightsSyncState.Syncing
                                }
                            pull()
                            slowReveal.cancel()
                        } else {
                            pull()
                        }
                    }
                    // An automatic pull that finished before it was ever shown
                    // stays silent through the confirmation too: the user never
                    // saw it start, so a "Synced" chip seconds later would be
                    // the same unrequested animation, only later.
                    if (trigger == SyncTrigger.Manual || revealed) {
                        _syncState.value = HighlightsSyncState.Synced
                        syncedResetJob =
                            scope.launch {
                                delay(SYNCED_DISPLAY_DURATION_MS)
                                _syncState.value = HighlightsSyncState.Idle
                            }
                    }
                } catch (_: Exception) {
                    _syncState.value = HighlightsSyncState.Idle
                }
            }
    }
}

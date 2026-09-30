package com.nexo.presentation.viewmodel.highlights

import com.nexo.data.remote.supabase.SupabaseProgressSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SYNCED_DISPLAY_DURATION_MS = 3000L

sealed interface HighlightsSyncState {
    data object Idle : HighlightsSyncState

    data object Syncing : HighlightsSyncState

    data object Synced : HighlightsSyncState
}

class SyncStateHolder(
    private val supabaseSync: SupabaseProgressSync?,
    private val scope: CoroutineScope,
) {
    private val _syncState = MutableStateFlow<HighlightsSyncState>(HighlightsSyncState.Idle)
    val syncState: StateFlow<HighlightsSyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var syncedResetJob: Job? = null

    fun syncHighlights(force: Boolean = false) {
        val sync = supabaseSync ?: return
        if (syncJob?.isActive == true && !force) return
        syncJob?.cancel()
        syncedResetJob?.cancel()
        syncJob =
            scope.launch {
                _syncState.value = HighlightsSyncState.Syncing
                try {
                    sync.pullAllHighlights()
                    _syncState.value = HighlightsSyncState.Synced
                    syncedResetJob =
                        scope.launch {
                            delay(SYNCED_DISPLAY_DURATION_MS)
                            _syncState.value = HighlightsSyncState.Idle
                        }
                } catch (_: Exception) {
                    _syncState.value = HighlightsSyncState.Idle
                }
            }
    }
}

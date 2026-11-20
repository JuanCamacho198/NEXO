package com.nexo.presentation.feature.library

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nexo.data.remote.sync.DriveSyncState
import com.nexo.ui.components.atoms.SyncStatusIndicator

/**
 * Inline sync pill for the Library toolbar.
 *
 * It used to float over the top-right corner of the screen, where it covered the
 * filter action; it now sits in the sort row and crossfades between states.
 */
@Composable
fun LibrarySyncStatus(
    syncError: String?,
    isSyncing: Boolean,
    modifier: Modifier = Modifier,
) {
    val syncState =
        when {
            syncError != null -> DriveSyncState.Error(syncError)
            isSyncing -> DriveSyncState.Running
            else -> DriveSyncState.Idle
        }
    Crossfade(targetState = syncState, modifier = modifier, label = "librarySync") { state ->
        SyncStatusIndicator(syncState = state)
    }
}

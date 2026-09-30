package com.nexo.presentation.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexo.data.remote.sync.DriveSyncState
import com.nexo.ui.components.atoms.SyncStatusIndicator

@Composable
fun LibrarySyncStatus(
    syncError: String?,
    isSyncing: Boolean,
) {
    val syncState =
        when {
            syncError != null -> DriveSyncState.Error(syncError)
            isSyncing -> DriveSyncState.Running
            else -> DriveSyncState.Idle
        }
    Box(modifier = Modifier.fillMaxSize()) {
        SyncStatusIndicator(syncState = syncState, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp))
    }
}

package com.nexo.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.data.remote.sync.DriveSyncState
import com.nexo.presentation.theme.NexoTheme

/**
 * Compact pill that surfaces the current cloud-sync state. Used in
 * toolbars/headers to give the user at-a-glance feedback on background
 * sync activity.
 *
 * Mapping (color, label) is hard-coded by [syncState]:
 * - `Idle` → green (`#4ADE80`) + `R.string.sync_status_synced`
 * - `Disabled` → gray (`#9CA3AF`) + `R.string.sync_status_off`
 * - `Running` → blue (`#60A5FA`) + `R.string.sync_status_syncing`
 * - `Error` → red (`#F87171`) + `R.string.sync_status_error`
 *
 * @param syncState Current sync state. See `data.remote.sync.DriveSyncState`.
 * @param modifier Modifier applied to the outer `Row`.
 *
 * **Visual**: 8dp circular dot + `labelSmall` label. The pill itself uses
 * `surfaceVariant` at 60% alpha and 12dp corner radius. Internal
 * spacing: 6dp between elements, 10dp horizontal × 4dp vertical padding.
 * **Behavior**: pure rendering. The dot color is a hard-coded hex
 * value (not from the theme) so the meaning is consistent across
 * themes — green/gray/blue/red are universally read as
 * ok/off/active/error.
 * **Recomposition**: recomposes when `syncState` changes.
 */
@Composable
fun SyncStatusIndicator(
    syncState: DriveSyncState,
    modifier: Modifier = Modifier,
) {
    val (dotColor, labelRes) =
        when (syncState) {
            is DriveSyncState.Idle -> Color(0xFF4ADE80) to R.string.sync_status_synced
            is DriveSyncState.Disabled -> Color(0xFF9CA3AF) to R.string.sync_status_off
            is DriveSyncState.Running -> Color(0xFF60A5FA) to R.string.sync_status_syncing
            is DriveSyncState.Error -> Color(0xFFF87171) to R.string.sync_status_error
            is DriveSyncState.AuthorizationNeeded -> Color(0xFFFBBF24) to R.string.sync_status_authorization_needed
        }
    val label = stringResource(labelRes)

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Status dot
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncStatusIndicator(syncState = DriveSyncState.Idle)
            SyncStatusIndicator(syncState = DriveSyncState.Running)
            SyncStatusIndicator(syncState = DriveSyncState.Error("Sync failed"))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncStatusIndicator(syncState = DriveSyncState.Idle)
            SyncStatusIndicator(syncState = DriveSyncState.Running)
            SyncStatusIndicator(syncState = DriveSyncState.Error("Sync failed"))
        }
    }
}

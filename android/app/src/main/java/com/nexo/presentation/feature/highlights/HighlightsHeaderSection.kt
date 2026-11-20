package com.nexo.presentation.feature.highlights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.AuthSession
import com.nexo.presentation.viewmodel.highlights.HighlightsSyncState
import com.nexo.ui.components.atoms.NexoIconButton
import com.nexo.ui.components.molecules.NexoTopAppBar
import com.nexo.ui.components.molecules.TopLevelVariant
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

/**
 * Highlights header: the same top-level bar as Library and Discover — avatar,
 * the screen title with its subtitle, and the search and refresh actions —
 * followed by a sync confirmation that slides in while syncing and right after.
 *
 * It replaces a "NEXO" brand bar stacked over a second, larger "Highlights"
 * title with a refresh disc floating beside it.
 */
@Composable
fun HighlightsHeaderSection(
    authSession: AuthSession?,
    onOpenAccount: () -> Unit,
    onSearchClick: () -> Unit,
    syncState: HighlightsSyncState,
    onSyncRefresh: () -> Unit,
) {
    val isSyncing = syncState is HighlightsSyncState.Syncing
    val isSynced = syncState is HighlightsSyncState.Synced

    NexoTopAppBar(
        title = stringResource(R.string.highlights_title),
        subtitle = stringResource(R.string.highlights_subtitle),
        variant = TopLevelVariant.Home,
        userInitial =
            authSession
                ?.displayName
                ?.trim()
                ?.take(1)
                ?.uppercase()
                ?.ifEmpty { null },
        avatarImageUrl = authSession?.photoUrl,
        avatarContentDescription = stringResource(R.string.home_avatar_content_description),
        onAvatarClick = onOpenAccount,
        backContentDescription = stringResource(R.string.nav_back),
        actions = {
            NexoIconButton(
                icon = NexoIcons.Search,
                contentDescription = stringResource(R.string.search_label),
                onClick = onSearchClick,
                size = NexoDimens.topBarActionTarget,
                iconSize = NexoDimens.topBarActionIcon,
            )
            Crossfade(targetState = isSyncing, label = "highlightsRefresh") { syncing ->
                if (syncing) {
                    Box(
                        modifier =
                            Modifier
                                .size(NexoDimens.topBarActionTarget)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    NexoIconButton(
                        icon = NexoIcons.Sync,
                        contentDescription = stringResource(R.string.highlights_refresh_content_description),
                        onClick = onSyncRefresh,
                        size = NexoDimens.topBarActionTarget,
                        iconSize = NexoDimens.topBarActionIcon,
                    )
                }
            }
        },
    )

    AnimatedVisibility(
        visible = isSyncing || isSynced,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            SyncPill(isSyncing = isSyncing)
        }
    }
}

@Composable
private fun SyncPill(isSyncing: Boolean) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Text(text = stringResource(R.string.highlights_syncing), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        } else {
            Icon(imageVector = NexoIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.accentGreen)
            Text(text = stringResource(R.string.highlights_synced), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.notification.Category
import com.nexo.domain.notification.Interruption
import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationGroup
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.NotificationSource
import com.nexo.domain.notification.NotificationTarget
import com.nexo.domain.notification.NotificationTimestamp
import com.nexo.domain.notification.Severity
import com.nexo.domain.notification.groupNotifications
import com.nexo.presentation.notification.UiEventToastSink
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.icons.NexoIcons

/**
 * Modal bottom sheet showing the real notification tray.
 *
 * Rows are grouped by `source::i18nKey` (see [groupNotifications]); the newest
 * entry of a group is the representative and the row shows the group's count.
 * Tapping a row marks the whole group read and, when the target resolves,
 * deep-links through [onOpen]; an unresolved target is a safe no-op that leaves
 * the row read on the tray.
 *
 * The message is rendered from the STORED `i18nKey` + `i18nParams` at display
 * time (never a resolved string), so a language switch re-renders history.
 *
 * @param groups Grouped tray entries, newest group first (the caller groups so
 *   the composable stays a pure renderer).
 * @param onOpen Invoked with the group's representative when its target
 *   resolves; the caller decides how to navigate.
 * @param onDismiss Invoked on swipe-down, scrim-tap, back-press or the close X.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSheet(
    groups: List<NotificationGroup>,
    onOpen: (Notification) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = System.currentTimeMillis()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notifications_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = NexoIcons.Close,
                        contentDescription = stringResource(R.string.reader_settings_close),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            NexoDivider()
            Spacer(modifier = Modifier.height(16.dp))

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.notifications_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                groups.forEachIndexed { index, group ->
                    NotificationRow(
                        group = group,
                        nowMs = now,
                        onClick = { if (group.representative.target != null) onOpen(group.representative) },
                    )
                    if (index != groups.lastIndex) {
                        NexoDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    group: NotificationGroup,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val notification = group.representative
    val context = LocalContext.current
    val message =
        UiEventToastSink.format(
            context = context,
            i18nKey = notification.i18nKey,
            params = notification.i18nParams,
        )
    val interactive = notification.target != null

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (interactive) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconFor(notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (group.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = relativeLabel(notification.createdAt, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (group.count > 1) {
            Text(
                text = stringResource(R.string.notifications_group_count, group.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (group.isUnread) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun relativeLabel(
    createdAt: Long,
    nowMs: Long,
): String {
    val relative = NotificationTimestamp.relative(createdAt, nowMs)
    return when (relative.bucket) {
        NotificationTimestamp.Bucket.JUST_NOW -> stringResource(R.string.notifications_time_now)
        NotificationTimestamp.Bucket.MINUTES -> stringResource(R.string.notifications_time_minutes, relative.amount.toInt())
        NotificationTimestamp.Bucket.HOURS -> stringResource(R.string.notifications_time_hours, relative.amount.toInt())
        NotificationTimestamp.Bucket.DAYS -> stringResource(R.string.notifications_time_days, relative.amount.toInt())
    }
}

private fun iconFor(notification: Notification): ImageVector =
    when (notification.source) {
        NotificationSource.IMPORT -> NexoIcons.CloudDownload
        NotificationSource.SYNC -> NexoIcons.Sync
        NotificationSource.STREAK, NotificationSource.GOAL -> NexoIcons.Trophy
        NotificationSource.LIBRARY, NotificationSource.ADDONS -> NexoIcons.LibraryBooks
        NotificationSource.UPDATE -> NexoIcons.Info
    }

@Preview(showBackground = true)
@Composable
private fun NotificationSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        NotificationSheet(
            groups =
                groupNotifications(
                    listOf(
                        Notification(
                            id = "1",
                            createdAt = System.currentTimeMillis() - 120_000,
                            source = NotificationSource.IMPORT,
                            category = Category.SYSTEM,
                            severity = Severity.SUCCESS,
                            interruption = Interruption.SILENT,
                            i18nKey = "notifications.event.import.success",
                            i18nParams = mapOf("p1" to "La Odisea"),
                        ),
                        Notification(
                            id = "2",
                            createdAt = System.currentTimeMillis() - 3_600_000,
                            source = NotificationSource.SYNC,
                            category = Category.SYSTEM,
                            severity = Severity.ERROR,
                            interruption = Interruption.IN_APP,
                            i18nKey = "notifications.event.sync.error",
                            i18nParams = mapOf("p1" to "offline"),
                            target = NotificationTarget.Route(NotificationRouteTarget.SYNC),
                        ),
                    ),
                ),
            onOpen = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NotificationSheetEmptyPreview() {
    NexoTheme(darkTheme = false) {
        NotificationSheet(groups = emptyList(), onOpen = {}, onDismiss = {})
    }
}

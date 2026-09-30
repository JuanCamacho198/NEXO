package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.icons.NexoIcons

/**
 * Plain-data model for a single row in [NotificationSheet].
 *
 * @property id Stable identifier (string) for the row. Currently
 *   used only as a key for the row's `Compose` identity.
 * @property icon Leading icon shown in a circular tinted badge.
 * @property title Notification headline. Rendered in
 *   `bodyLarge` semibold when [isUnread] is `true`, normal weight
 *   otherwise. Capped at 1 line with ellipsis.
 * @property body Notification body. Rendered in `bodyMedium`
 *   `onSurfaceVariant`, capped at 2 lines with ellipsis.
 * @property isUnread When `true`, an 8dp `colorScheme.primary` dot
 *   is rendered to the right of the body. Defaults to `true` (most
 *   notifications are unread when first shown).
 */
data class NotificationItem(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val isUnread: Boolean = true,
)

/**
 * Modal bottom sheet that shows a list of in-app notifications.
 * Currently uses three hard-coded "mock" notifications (welcome,
 * reading streak, library); the data list lives inside the
 * composable and is not parameterized.
 *
 * @param onDismiss Invoked on swipe-down, scrim-tap, back-press, or
 *   when the user taps the close X in the header. The sheet does
 *   NOT auto-dismiss on item tap (rows are non-interactive in the
 *   current design).
 *
 * **Visual**: standard Material 3 `ModalBottomSheet` with
 *   `surface` background and 16dp top corners. Header: "Notifications"
 *   `titleLarge` bold + close X. Below: a `NexoDivider`, then
 *   3 rows of `NotificationRow` (icon badge + title + body + unread
 *   dot), separated by dividers (except after the last row).
 * **Behavior**: tap any row → no-op (rows are display-only).
 *   Tap the close X or swipe → [onDismiss].
 * **Recomposition**: recomposes when [onDismiss] changes. The
 *   mock notification list is rebuilt on every composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val mockNotifications =
        listOf(
            NotificationItem(
                id = "1",
                icon = NexoIcons.Trophy,
                title = stringResource(R.string.notifications_mock_welcome_title),
                body = stringResource(R.string.notifications_mock_welcome_body),
            ),
            NotificationItem(
                id = "2",
                icon = NexoIcons.ChartLine,
                title = stringResource(R.string.notifications_mock_streak_title),
                body = stringResource(R.string.notifications_mock_streak_body),
            ),
            NotificationItem(
                id = "3",
                icon = NexoIcons.LibraryBooks,
                title = stringResource(R.string.notifications_mock_library_title),
                body = stringResource(R.string.notifications_mock_library_body),
            ),
        )

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
            // Header
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

            if (mockNotifications.isEmpty()) {
                Text(
                    text = stringResource(R.string.notifications_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                mockNotifications.forEach { notification ->
                    NotificationRow(item = notification)
                    if (notification != mockNotifications.last()) {
                        NexoDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon circle
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }

        // Title + body
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Unread dot
        if (item.isUnread) {
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

@Preview(showBackground = true)
@Composable
private fun NotificationSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        NotificationSheet(onDismiss = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun NotificationSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        NotificationSheet(onDismiss = {})
    }
}

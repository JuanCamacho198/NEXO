package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.components.atoms.NexoIconButton
import com.nexo.ui.icons.NexoIcons

/**
 * Top app header: avatar (or initials) + title on the left, optional
 * search and notifications icon buttons on the right, followed by any
 * caller-supplied trailing action icons.
 *
 * @param title Header text rendered next to the avatar in
 *   `titleMedium` semibold.
 * @param modifier Modifier applied to the outer `Row`.
 * @param avatarImageUrl Remote avatar URL. When `null`/blank, the
 *   [avatarInitials] fallback is used. Default `null`.
 * @param avatarInitials Two-character fallback for the avatar circle
 *   (e.g. `"JS"`). Default `"NP"`. Will be uppercased and truncated
 *   to 2 chars by [NexoAvatar].
 * @param onAvatarClick Optional avatar click handler. When `null`, the
 *   avatar is non-interactive.
 * @param avatarContentDescription Optional accessibility label for the
 *   avatar (e.g. "Open account settings").
 * @param onSearchClick Optional search-button callback. When `null`,
 *   the search icon is not rendered.
 * @param onNotificationsClick Optional notifications-button callback.
 *   When `null`, the bell icon is not rendered.
 * @param trailingActions Additional icon buttons rendered after the
 *   built-in search/notifications buttons. Each pair is
 *   `(icon, onClick)`. The `contentDescription` for these is
 *   intentionally `""` (decorative — pair with an a11y label outside
 *   the header if needed).
 *
 * **Visual**: 40dp avatar + 12dp gap + title on the left. On the
 *   right: search icon (if [onSearchClick]), notifications icon (if
 *   [onNotificationsClick]), then any [trailingActions] — all 40dp
 *   [NexoIconButton]s with 4dp spacing. 16dp top padding.
 * **Behavior**: each visible icon calls its respective callback. No
 *   internal state.
 * **Recomposition**: recomposes when `title`, `avatarImageUrl`,
 *   `avatarInitials`, or any callback/action changes.
 */
@Composable
fun NexoHeader(
    title: String,
    modifier: Modifier = Modifier,
    avatarImageUrl: String? = null,
    avatarInitials: String = "NP",
    onAvatarClick: (() -> Unit)? = null,
    avatarContentDescription: String? = null,
    onSearchClick: (() -> Unit)? = null,
    onNotificationsClick: (() -> Unit)? = null,
    trailingActions: List<Pair<ImageVector, () -> Unit>> = emptyList(),
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NexoAvatar(
                imageUrl = avatarImageUrl,
                initials = avatarInitials,
                size = 40.dp,
                onClick = onAvatarClick,
                contentDescription = avatarContentDescription,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            onSearchClick?.let {
                NexoIconButton(
                    icon = NexoIcons.Search,
                    contentDescription = stringResource(R.string.search_label),
                    onClick = it,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
            }
            onNotificationsClick?.let {
                NexoIconButton(
                    icon = NexoIcons.Notifications,
                    contentDescription = stringResource(R.string.notifications_title),
                    onClick = it,
                    size = 40.dp,
                )
            }
            trailingActions.forEach { (icon, onClick) ->
                NexoIconButton(
                    icon = icon,
                    contentDescription = "",
                    onClick = onClick,
                    size = 40.dp,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoHeaderDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoHeader(
            title = "My Library",
            avatarInitials = "JS",
            onAvatarClick = {},
            avatarContentDescription = "Open account settings",
            onSearchClick = {},
            onNotificationsClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoHeaderLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoHeader(
            title = "My Library",
            avatarInitials = "JS",
            onAvatarClick = {},
            avatarContentDescription = "Open account settings",
            onSearchClick = {},
            onNotificationsClick = {},
        )
    }
}

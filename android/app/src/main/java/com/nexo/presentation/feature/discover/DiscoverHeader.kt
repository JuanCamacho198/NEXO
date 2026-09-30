package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.icons.NexoIcons

/**
 * Discover header: title, subtitle (result-count variant) and the signed-in
 * user's avatar.
 *
 * @param title Screen title (localized by the caller).
 * @param subtitle Supporting line — the default section description, or the
 *   localized result count while results are shown.
 * @param userInitial Single-character initial for the signed-in user. When
 *   null (signed out or session not resolved) a neutral placeholder renders
 *   instead — the header never crashes.
 * @param avatarContentDescription Accessibility label for the avatar.
 * @param onAvatarClick Optional avatar tap handler.
 */
@Composable
fun DiscoverHeader(
    title: String,
    subtitle: String,
    userInitial: String?,
    avatarContentDescription: String,
    modifier: Modifier = Modifier,
    onAvatarClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = NexoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val initial = userInitial?.trim()?.takeIf { it.isNotEmpty() }
        if (initial != null) {
            NexoAvatar(
                imageUrl = null,
                initials = initial,
                size = 40.dp,
                onClick = onAvatarClick,
                contentDescription = avatarContentDescription,
            )
        } else {
            // Neutral placeholder: signed out (or session unresolved). No crash.
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NexoColors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.Person,
                    contentDescription = avatarContentDescription,
                    modifier = Modifier.size(20.dp),
                    tint = NexoColors.textSecondary,
                )
            }
        }
    }
}

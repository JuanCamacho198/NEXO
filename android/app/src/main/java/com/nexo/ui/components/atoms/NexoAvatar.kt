package com.nexo.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexo.presentation.theme.NexoTheme

/**
 * Circular user avatar: either a Coil-loaded remote image, or a
 * primary-colored disc showing the user's initials. The circle is
 * `colorScheme.primary` so the initials branch is legible in both
 * light and dark themes.
 *
 * @param imageUrl Remote image URL. When `null` or blank, the initials
 *   fallback is rendered.
 * @param initials String from which the first two characters are
 *   extracted, upper-cased, and rendered as the fallback. Should be
 *   short (e.g. `"JS"` for "Juan S.").
 * @param modifier Modifier applied to the outer `Box`.
 * @param size Diameter of the avatar. Default `48.dp`. Width and height
 *   are forced equal via `Modifier.size(size)` on the `Box`.
 * @param onClick Optional click handler. When `null`, the avatar is
 *   non-interactive. When set, the interactive region grows to the
 *   platform minimum (≥48dp) via an enlarged clickable wrapper while
 *   the visual circle stays at [size].
 * @param contentDescription Optional accessibility label. Exposed as a
 *   semantics `contentDescription` when provided (e.g. "Open account
 *   settings").
 *
 * **Visual**: circle clipped to `CircleShape`, filled with
 * `colorScheme.primary`. On image: `AsyncImage` with
 * `ContentScale.Crop` filling the circle. On fallback: `titleMedium`
 * bold text in `colorScheme.onPrimary`, showing
 * `initials.take(2).uppercase()`.
 * **Behavior**: pure rendering when [onClick] is `null`; clicking the
 * avatar invokes [onClick] and the Coil `AsyncImage` pipeline is kept.
 * **Recomposition**: recomposes when `imageUrl`, `initials`, `size`,
 * `onClick`, or `contentDescription` change.
 */
@Composable
fun NexoAvatar(
    imageUrl: String?,
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    // The interactive (clickable) wrapper is at least 48dp so the touch
    // target meets the accessibility minimum even when the visual circle
    // is smaller (e.g. 40dp in headers). The visual disc keeps [size].
    val interactiveSize = if (onClick != null && size < 48.dp) 48.dp else size
    Box(
        modifier =
            modifier
                .size(if (onClick != null) interactiveSize else size)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ).then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(
                    text = initials.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoAvatarDarkPreview() {
    NexoTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            NexoAvatar(imageUrl = null, initials = "JS")
            NexoAvatar(
                imageUrl = "https://example.com/avatar.png",
                initials = "JS",
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoAvatarLightPreview() {
    NexoTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            NexoAvatar(imageUrl = null, initials = "JS")
            NexoAvatar(
                imageUrl = "https://example.com/avatar.png",
                initials = "JS",
                onClick = {},
            )
        }
    }
}

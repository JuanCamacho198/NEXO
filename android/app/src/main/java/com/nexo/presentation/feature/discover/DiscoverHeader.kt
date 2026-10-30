package com.nexo.presentation.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nexo.ui.components.molecules.NexoTopAppBar
import com.nexo.ui.components.molecules.TopLevelVariant

/**
 * Discover header: the shared top-level bar with the screen title, a
 * result-count subtitle and the signed-in user's avatar.
 *
 * Delegates everything visual to [NexoTopAppBar] so Discover shares its bar
 * height, padding, title role and trailing-action geometry with the rest of the
 * app instead of re-deriving them.
 *
 * @param title Screen title (localized by the caller).
 * @param subtitle Supporting line — the default section description, or the
 *   localized result count while results are shown.
 * @param userInitial Single-character initial for the signed-in user. When
 *   null (signed out or session not resolved) the bar renders its neutral
 *   person placeholder instead — the header never crashes.
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
    NexoTopAppBar(
        title = title,
        subtitle = subtitle,
        variant = TopLevelVariant.Home,
        modifier = modifier,
        userInitial = userInitial?.trim()?.takeIf { it.isNotEmpty() },
        avatarContentDescription = avatarContentDescription,
        onAvatarClick = onAvatarClick,
    )
}

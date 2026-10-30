package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.components.atoms.NexoIconButton
import com.nexo.ui.icons.NexoIcons

/** Height of the top-level (avatar) bar — the `.pen` `Component/TopAppBar/Home` spec. */
private val TopLevelBarHeight = 64.dp

/** Height of the detail (back-arrow) bar — the `.pen` top bar specimen spec. */
private val DetailBarHeight = 56.dp

/** Horizontal inset on a top-level bar. Matches the `.pen` `screen_pad`. */
private val TopLevelBarHorizontalPadding = 20.dp

/** Horizontal inset on a detail bar. Matches the `.pen` top bar specimen. */
private val DetailBarHorizontalPadding = 16.dp

/** Vertical inset on the top-level bar. The `.pen` spec pairs it with [TopLevelBarHorizontalPadding]. */
private val TopLevelBarVerticalPadding = 16.dp

/** Diameter of the avatar and of every trailing icon target in the bar. */
private val BarAvatarSize = NexoDimens.topBarAvatarSize

/** Diameter of trailing icon buttons in the top-level bar. */
private val BarActionSize = 40.dp

/** Height of the streak pill — shorter than [BarActionSize] but still tappable. */
private val BarStreakTargetHeight = 24.dp

/** Horizontal inset inside the streak pill. */
private val BarStreakHorizontalPadding = 10.dp

/** Gap between the streak flame glyph and its count. */
private val BarStreakGap = 4.dp

/** Side length of the flame glyph inside the streak pill. */
private val BarStreakIconSize = 14.dp

/**
 * The one top app bar for NEXO.
 *
 * Replaces four independent constructions (`NexoHeader`, `LibraryHeader`,
 * `DiscoverHeader`'s inline row, and per-screen Material `TopAppBar` copies)
 * with two spec-backed variants taken from `design/nextPage-movil.pen`:
 *
 * - [TopLevelVariant.Home] — the avatar bar, 64dp tall, 16dp/20dp padding,
 *   used by the four bottom-nav destinations.
 * - [TopLevelVariant.Detail] — the back-arrow bar, 56dp tall, 16dp/16dp
 *   padding, used by pushed sub-screens.
 *
 * Only the vertical metrics and the leading affordance differ between them;
 * title typography, colours and content alignment are shared so the two read
 * as the same bar at different densities.
 *
 * @param title Bar title. Rendered `titleMedium` SemiBold in both variants —
 *   the `.pen` specifies 18sp/600, and `titleMedium` SemiBold is its closest
 *   shipped role. Using the role keeps the bar immune to per-screen size drift.
 * @param variant Which of the two spec bars to render.
 * @param modifier Modifier applied to the outer box.
 * @param subtitle Optional supporting line rendered under the title in
 *   `bodyMedium` / `text-secondary`. Null renders no second line.
 * @param userInitial Single glyph shown in the avatar. Used only by
 *   [TopLevelVariant.Home]. Null renders a neutral person placeholder instead
 *   of an empty circle.
 * @param avatarContentDescription Accessibility label for the avatar.
 * @param avatarImageUrl Remote avatar URL; falls back to [userInitial].
 * @param onAvatarClick Optional avatar tap handler. Null makes it inert.
 * @param onBack Back handler for [TopLevelVariant.Detail]; when null no leading
 *   affordance is drawn.
 * @param onSearchClick Optional search action. Null omits the button.
 * @param streakDays Current reading streak in days. When greater than zero the
 *   bar renders a compact flame badge with the count, in the slot that
 *   otherwise holds search. Zero omits the badge — a streak of none is not an
 *   announcement.
 * @param onStreakClick Optional streak badge tap handler. Null keeps the badge
 *   as a read-only indicator.
 * @param onNotificationsClick Optional notifications action. Null omits it.
 * @param unreadNotificationsCount When > 0 a dot is drawn on the bell and the
 *   bell's label switches to the badge description.
 * @param actions Extra trailing icon buttons, rendered after the built-ins.
 *
 * **Behavior**: no internal state; every action is hoisted.
 * **Accessibility**: the avatar, back arrow and both built-in buttons carry
 * localized content descriptions. Icons passed through [actions] must carry
 * their own label at the call site.
 */
@Composable
fun NexoTopAppBar(
    title: String,
    variant: TopLevelVariant,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    userInitial: String? = null,
    avatarContentDescription: String? = null,
    avatarImageUrl: String? = null,
    onAvatarClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    streakDays: Int = 0,
    onStreakClick: (() -> Unit)? = null,
    onNotificationsClick: (() -> Unit)? = null,
    unreadNotificationsCount: Int = 0,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(NexoColors.header)
                .then(
                    when (variant) {
                        TopLevelVariant.Home ->
                            Modifier
                                .height(TopLevelBarHeight)
                                .padding(
                                    horizontal = TopLevelBarHorizontalPadding,
                                    vertical = TopLevelBarVerticalPadding,
                                )

                        TopLevelVariant.Detail ->
                            Modifier
                                .height(DetailBarHeight)
                                .padding(horizontal = DetailBarHorizontalPadding)
                    },
                ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexoDimens.topBarGap),
        ) {
            when (variant) {
                TopLevelVariant.Home ->
                    if (userInitial.isNullOrBlank()) {
                        // Neutral placeholder: signed out (or session unresolved).
                        Box(
                            modifier =
                                Modifier
                                    .size(BarAvatarSize)
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
                    } else {
                        NexoAvatar(
                            imageUrl = avatarImageUrl,
                            initials = userInitial,
                            size = BarAvatarSize,
                            onClick = onAvatarClick,
                            contentDescription = avatarContentDescription,
                        )
                    }

                TopLevelVariant.Detail ->
                    if (onBack != null) {
                        NexoIconButton(
                            icon = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            onClick = onBack,
                            size = BarActionSize,
                            iconSize = 24.dp,
                            containerColor = Color.Transparent,
                        )
                    }
            }

            TopBarTitle(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))

            TopBarActions(
                onSearchClick = onSearchClick,
                streakDays = streakDays,
                onStreakClick = onStreakClick,
                onNotificationsClick = onNotificationsClick,
                unreadNotificationsCount = unreadNotificationsCount,
                actions = actions,
            )
        }
    }
}

@Composable
private fun TopBarTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NexoDimens.topBarTitleGap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopBarActions(
    onSearchClick: (() -> Unit)?,
    streakDays: Int,
    onStreakClick: (() -> Unit)?,
    onNotificationsClick: (() -> Unit)?,
    unreadNotificationsCount: Int,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexoDimens.topBarActionGap),
    ) {
        if (onSearchClick != null) {
            NexoIconButton(
                icon = NexoIcons.Search,
                contentDescription = stringResource(R.string.search_label),
                onClick = onSearchClick,
                size = BarActionSize,
                iconSize = 20.dp,
                containerColor = NexoColors.surface,
            )
        }
        if (streakDays > 0) {
            StreakBadge(days = streakDays, onClick = onStreakClick)
        }
        if (onNotificationsClick != null) {
            Box {
                NexoIconButton(
                    icon = NexoIcons.Notifications,
                    contentDescription =
                        if (unreadNotificationsCount > 0) {
                            stringResource(R.string.notifications_badge_content_description)
                        } else {
                            stringResource(R.string.notifications_title)
                        },
                    onClick = onNotificationsClick,
                    size = BarActionSize,
                    containerColor = NexoColors.surface,
                )
                if (unreadNotificationsCount > 0) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(NexoDimens.topBarBadgeSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
        }
        actions()
    }
}

/**
 * Reading-streak indicator for the top bar's action slot: a flame glyph and
 * the day count in a pill.
 *
 * The pill is intentionally shorter than the 40dp circular actions so the row
 * reads as "one tall status indicator plus round buttons" rather than as a row
 * of equal circles. Its touch target is taller than the pill and never wider
 * than [BarActionSize], so a long streak cannot squeeze the title.
 *
 * @param days Streak length in days; rendered verbatim.
 * @param onClick Optional tap handler. Null renders a read-only indicator.
 */
@Composable
private fun StreakBadge(
    days: Int,
    onClick: (() -> Unit)?,
) {
    val label = stringResource(R.string.home_streak_badge_content_description, days)
    Box(
        modifier =
            Modifier
                .height(BarStreakTargetHeight)
                .clip(CircleShape)
                .background(NexoColors.surface)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(horizontal = BarStreakHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BarStreakGap),
        ) {
            Icon(
                imageVector = NexoIcons.Flame,
                contentDescription = null,
                tint = NexoColors.streak,
                modifier = Modifier.size(BarStreakIconSize),
            )
            Text(
                text = days.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** The two spec-backed bar densities. */
enum class TopLevelVariant { Home, Detail }

@Preview(showBackground = true)
@Composable
private fun NexoTopAppBarHomeDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoTopAppBar(
            title = "NEXO",
            variant = TopLevelVariant.Home,
            userInitial = "J",
            avatarContentDescription = "Your profile",
            onSearchClick = {},
            onNotificationsClick = {},
            unreadNotificationsCount = 1,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTopAppBarHomeLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoTopAppBar(
            title = "NEXO",
            variant = TopLevelVariant.Home,
            userInitial = "J",
            avatarContentDescription = "Your profile",
            onSearchClick = {},
            onNotificationsClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTopAppBarDetailPreview() {
    NexoTheme(darkTheme = true) {
        NexoTopAppBar(
            title = "Discover",
            variant = TopLevelVariant.Detail,
            subtitle = "12 results",
            onBack = {},
        )
    }
}

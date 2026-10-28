package com.nexo.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexo.R
import com.nexo.presentation.screen.HomeTags
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.icons.NexoIcons

/**
 * Height of the hero once the library is populated.
 *
 * The `.pen` `hero_section` of `JlX0K` is 289dp, shorter than the empty-state
 * hero. The photo underneath stays at [HeroPhotoHeight] and is clipped at the
 * bottom, so the shorter hero is a crop of the same image rather than a
 * differently-scaled one — the brand row and the top of the valley keep their
 * proportions on every screen.
 */
internal val CollapsedHeroHeight = 289.dp

/**
 * Visible height of the hero when the library is empty.
 *
 * The `.pen` `hero_section` of `DbQ9L` (`K8s4Py`) is 420dp: tall enough for the
 * photo, the brand row and the "discover new stories" copy with its amber CTA.
 */
internal val HeroHeight = 420.dp

/**
 * Height of the hero photo itself, independent of the visible hero.
 *
 * Both states paint a 420dp image and let the frame crop it; the populated
 * state simply shows less of it. Keeping the photo at one size means the crop
 * never rescales the cabin, so the two states are visually continuous.
 */
internal val HeroPhotoHeight = 420.dp

/** Vertical offset of the brand row from the hero's top edge (`.pen` `header_row` y=40). */
private val HeroHeaderTopInset = 40.dp

/** Vertical offset of the hero copy (`.pen` `hero_message` y=250). */
private val HeroCopyTopInset = 210.dp

/**
 * Vertical offset of the hero copy in the populated state (`.pen` `W8bma` y=97).
 *
 * The copy sits near the top because the hero is shorter: the brand row claims
 * the first ~76dp, so the title starts just below it instead of at the empty
 * state's y=250, which would fall outside a 289dp frame.
 */
private val CollapsedHeroCopyTopInset = 97.dp

/** Title measure from the `.pen` `hero_title` node; keeps the line break predictable. */
private val HeroTitleWidth = 280.dp

/** Diameter of the circular streak and notification actions. */
private val HeroActionSize = 36.dp

/** Side length of the glyph inside a hero action. */
private val HeroActionIconSize = 18.dp

/**
 * Character of the fade painted over the hero photo.
 *
 * The photo stays clear for the first third, dims through the middle, and is
 * fully covered by `bg_main` at the bottom edge so the hero dissolves into the
 * page instead of ending on a hard line.
 */
private const val HeroFadeStartSolid = 0.45f
private const val HeroFadeDimAlpha = 0.85f

/**
 * Full-bleed hero for the first-session Home: photo, brand lockup with the
 * user avatar, the streak and notification actions, and the "discover new
 * stories" copy with its amber call to action.
 *
 * The photo is cropped (`ContentScale.Crop`) and the [R.drawable.home_hero]
 * asset is `nodpi`, so the hero fills whatever width the phone gives it without
 * the image being resampled per density bucket.
 *
 * @param avatarUrl User's profile photo URL, or null to fall back to the
 *   initials disc. Same contract as the populated Home's top bar.
 * @param userName Display name the fallback initials are taken from.
 * @param streakDays Current reading streak. Zero omits the count, keeping the
 *   flame as a plain affordance rather than announcing a streak of none.
 * @param onAvatarClick Opens the account surface.
 * @param onStreakClick Opens statistics.
 * @param onNotificationsClick Opens the notification sheet.
 * @param onExplore Invoked by the amber CTA.
 */
@Composable
fun HomeHeroSection(
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    userName: String = "",
    streakDays: Int = 0,
    onAvatarClick: (() -> Unit)? = null,
    onStreakClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
) {
    HeroFrame(height = HeroHeight, modifier = modifier) {
        HeroHeaderRow(
            avatarUrl = avatarUrl,
            userName = userName,
            streakDays = streakDays,
            onAvatarClick = onAvatarClick,
            onStreakClick = onStreakClick,
            onNotificationsClick = onNotificationsClick,
            modifier = Modifier.padding(top = HeroHeaderTopInset, start = NexoDimens.spacingLg, end = NexoDimens.spacingLg),
        )
        Spacer(modifier = Modifier.height(HeroCopyTopInset))
        HeroCopy(
            onExplore = onExplore,
            modifier = Modifier.padding(horizontal = NexoDimens.spacingLg),
        )
    }
}

/**
 * Full-bleed hero for a Home that already has books: the same photo, fade and
 * brand row as [HomeHeroSection], shortened to [CollapsedHeroHeight].
 *
 * The copy and the amber CTA are deliberately absent — with a library to show,
 * the screen's own content is the message, and the hero only carries the user's
 * identity and the two shell actions.
 *
 * @see HomeHeroSection for the parameter contract; they are identical.
 */
@Composable
fun CollapsedHomeHeroSection(
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    userName: String = "",
    streakDays: Int = 0,
    onAvatarClick: (() -> Unit)? = null,
    onStreakClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
) {
    HeroFrame(height = CollapsedHeroHeight, modifier = modifier) {
        HeroHeaderRow(
            avatarUrl = avatarUrl,
            userName = userName,
            streakDays = streakDays,
            onAvatarClick = onAvatarClick,
            onStreakClick = onStreakClick,
            onNotificationsClick = onNotificationsClick,
            onSearchClick = onSearchClick,
            modifier = Modifier.padding(top = HeroHeaderTopInset, start = NexoDimens.spacingLg, end = NexoDimens.spacingLg),
        )
        Spacer(modifier = Modifier.height(CollapsedHeroCopyTopInset - HeroHeaderTopInset - HeroActionSize))
        HeroCopy(
            onExplore = onExplore,
            modifier = Modifier.padding(horizontal = NexoDimens.spacingLg),
        )
    }
}

/**
 * Shared shell of every hero variant: the cropped photo, the fade that
 * dissolves its bottom edge into the page, and a full-size column the caller
 * fills with its own content.
 *
 * Both variants compose from this so a change to the image, the fade or the
 * container — and [HomeTags.HERO], which anchors the E2E flow — reaches the
 * full and the collapsed hero at once.
 *
 * @param height Container height. [HeroHeight] for the empty library,
 *   [CollapsedHeroHeight] once there are books.
 */
@Composable
private fun HeroFrame(
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .testTag(HomeTags.HERO),
    ) {
        AsyncHeroImage()
        HeroFade(visibleHeight = height)
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}

/**
 * The hero photo, painted at [HeroPhotoHeight] regardless of the visible frame.
 *
 * Both hero variants show the same 420dp image anchored to the top; the shorter
 * populated hero simply clips it. Sizing the photo to the frame instead would
 * rescale the cabin between states, so the crop is what carries the difference.
 *
 * The fade is painted over the photo's own [HeroPhotoHeight] so its stops stay
 * proportional to the image, not to the shorter visible frame.
 */
@Composable
private fun BoxScope.AsyncHeroImage() {
    AsyncImage(
        model = R.drawable.home_hero,
        contentDescription = stringResource(R.string.home_hero_image_content_description),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(HeroPhotoHeight).align(Alignment.TopStart),
    )
}

/**
 * The fade painted over the hero photo.
 *
 * Transparent at the top, opaque `bg_main` at the bottom, matching the `.pen`
 * `hero_fade` (`SnLhZ` / `O2sL7`): the image stays visible up top and dissolves
 * into the page behind the copy.
 *
 * Height is the VISIBLE hero height, not the photo's — the fade must complete
 * its transition inside the frame. Painting it at the photo height and clipping
 * would cut the gradient at ~69% and leave a hard edge where the hero ends.
 *
 * `Brush.verticalGradient` maps `0f` to the TOP and `1f` to the BOTTOM, so the
 * stops run transparent (top) → dimmed → opaque `bg_main` (bottom), in ascending
 * order. Compose reads the positions from the pairs, so they MUST ascend; a
 * descending list renders as a broken gradient rather than a reversed one.
 */
@Composable
private fun BoxScope.HeroFade(visibleHeight: Dp) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(visibleHeight)
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to Color.Transparent,
                                HeroFadeStartSolid to NexoColors.background.copy(alpha = HeroFadeDimAlpha),
                                1f to NexoColors.background,
                            ),
                    ),
                ),
    )
}

@Composable
private fun HeroHeaderRow(
    avatarUrl: String?,
    userName: String,
    streakDays: Int,
    onAvatarClick: (() -> Unit)?,
    onStreakClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(HomeTags.HERO_BRAND_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
            NexoAvatar(
                imageUrl = avatarUrl,
                initials = userName.take(1).uppercase(),
                size = NexoDimens.heroAvatarSize,
                onClick = onAvatarClick,
                contentDescription = stringResource(R.string.home_avatar_content_description),
            )
            Column {
                Text(
                    text = stringResource(R.string.home_nexo_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NexoColors.textPrimary,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
            if (onSearchClick != null) {
                HeroActionButton(
                    icon = NexoIcons.Search,
                    contentDescription = stringResource(R.string.search_label),
                    onClick = onSearchClick,
                )
            }
            HeroActionButton(
                icon = NexoIcons.Flame,
                contentDescription = stringResource(R.string.home_streak_content_description),
                badgeCount = streakDays,
                onClick = onStreakClick,
            )
            HeroActionButton(
                icon = NexoIcons.Notifications,
                contentDescription = stringResource(R.string.notifications_title),
                onClick = onNotificationsClick,
            )
        }
    }
}

/**
 * Circular ghost action on the hero: a translucent surface disc with a hairline
 * border, optionally carrying a numeric badge.
 */
@Composable
private fun HeroActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Box {
        Box(
            modifier =
                Modifier
                    .size(HeroActionSize)
                    .clip(CircleShape)
                    .background(NexoColors.surface.copy(alpha = HeroActionSurfaceAlpha))
                    .border(1.dp, NexoColors.borderSubtle, CircleShape)
                    .clickable(onClickLabel = contentDescription, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(HeroActionIconSize),
                tint = NexoColors.textPrimary,
            )
        }
        if (badgeCount > 0) {
            Text(
                text = badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = NexoColors.background,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(NexoColors.accentYellow)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Alpha of the hero action disc — the photo must stay visible through it. */
private const val HeroActionSurfaceAlpha = 0.72f

@Composable
private fun HeroCopy(
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(HomeTags.HERO_MESSAGE),
        verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm),
    ) {
        Text(
            text = stringResource(R.string.home_hero_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = NexoColors.textPrimary,
            modifier = Modifier.width(HeroTitleWidth),
        )
        Text(
            text = stringResource(R.string.home_hero_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NexoColors.textPrimary,
        )
        Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
        ExplorePill(onClick = onExplore)
    }
}

/**
 * The amber call to action on the hero.
 *
 * Modifier order follows Compose's outside-in rule: `clip` shapes the clickable
 * surface, `background` fills exactly that shape, and `padding` INSETS the
 * content inside it. Putting `padding` before `clip`/`background` shrinks the
 * decorated area instead of insetting the label, which renders the pill as a
 * bare lozenge — the original defect this replaces.
 */
@Composable
private fun ExplorePill(onClick: () -> Unit) {
    val contentDescription = stringResource(R.string.home_hero_cta_content_description)
    Row(
        modifier =
            Modifier
                .testTag(HomeTags.HERO_CTA)
                .clip(CircleShape)
                .background(NexoColors.accentYellow)
                .clickable(onClickLabel = contentDescription, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 14.dp)
                .widthIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm),
    ) {
        Text(
            text = stringResource(R.string.home_hero_cta),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = NexoColors.background,
        )
        Icon(
            imageVector = NexoIcons.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = NexoColors.background,
        )
    }
}

package com.nexo.ui.components.molecules

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.screen.NavTags
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

data class BottomNavItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    /** Glyph shown while the tab is active; defaults to [icon] for tabs without a pair. */
    val selectedIcon: ImageVector = icon,
)

/**
 * Bottom navigation bar with icon+label tabs and a Material 3 active-tab pill.
 *
 * Equal-width tabs spread across the available width. The active tab shows a
 * 56×32 primary-tinted pill behind its icon that grows out from the centre, and
 * a primary icon + label; inactive tabs use the secondary tint.
 *
 * **Theme**: the bar is `surfaceContainer` with a 1dp `borderSubtle` hairline
 * on top. Tonal contrast alone separated it in light mode, but in dark mode the
 * bar and the page are close enough that the bar dissolved into the content.
 *
 * **Behavior**: tap a tab → [onTabSelected] with its `route`. Each tab is
 * [selectable] with `Role.Tab`; the icon carries no content description so
 * TalkBack reads the label once instead of "Home, Home".
 *
 * @param destinations Ordered list of tabs to render.
 * @param currentRoute Currently active route, or `null` if no tab is active.
 * @param onTabSelected Invoked with the selected tab's `route`.
 * @param modifier Modifier applied to the outer `Surface`.
 */
@Composable
fun NexoBottomNavBar(
    destinations: List<BottomNavItem>,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairline = nexoColors.borderSubtle
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { dest ->
                val isSelected = currentRoute == dest.route
                val navTag =
                    when (dest.route) {
                        NexoDestination.Home.route -> NavTags.HOME
                        NexoDestination.Library.route -> NavTags.LIBRARY
                        NexoDestination.Highlights.route -> NavTags.HIGHLIGHTS
                        NexoDestination.Settings.route -> NavTags.SETTINGS
                        else -> dest.route
                    }
                NexoBottomNavTab(
                    item = dest,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f).testTag(navTag),
                    onClick = { onTabSelected(dest.route) },
                )
            }
        }
    }
}

/**
 * A single selectable tab. The pill has a fixed 56×32 slot and only its scale
 * and alpha animate, so switching tabs never shifts the icon or the label; the
 * icon itself no longer scales up, which used to make it jump.
 */
@Composable
private fun NexoBottomNavTab(
    item: BottomNavItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val activeTint = MaterialTheme.colorScheme.primary
    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant

    val tint by animateColorAsState(
        targetValue = if (isSelected) activeTint else inactiveTint,
        animationSpec = tween(200),
        label = "navTint",
    )
    val pill by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "navPill",
    )

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .selectable(
                    selected = isSelected,
                    role = Role.Tab,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(PillWidth, PillHeight), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = 0.5f + 0.5f * pill
                            alpha = pill
                        }.clip(CircleShape)
                        .background(activeTint.copy(alpha = PillAlpha)),
            )
            Icon(
                imageVector = if (isSelected) item.selectedIcon else item.icon,
                // The label below already names the tab; describing the icon too
                // made TalkBack announce every tab twice.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NexoDimens.iconNavBar),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(id = item.labelRes),
            fontSize = 12.sp,
            color = tint,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private val PillWidth = 56.dp
private val PillHeight = 32.dp
private const val PillAlpha = 0.16f

private val previewDestinations =
    listOf(
        BottomNavItem(
            route = NexoDestination.Home.route,
            labelRes = R.string.nav_home,
            icon = NexoIcons.HomeOutline,
            selectedIcon = NexoIcons.Home,
        ),
        BottomNavItem(
            route = NexoDestination.Library.route,
            labelRes = R.string.nav_library,
            icon = NexoIcons.LibraryOutline,
            selectedIcon = NexoIcons.Library,
        ),
        BottomNavItem(
            route = NexoDestination.Highlights.route,
            labelRes = R.string.nav_highlights,
            icon = NexoIcons.Highlights,
            selectedIcon = NexoIcons.HighlightsFilled,
        ),
        BottomNavItem(
            route = NexoDestination.Settings.route,
            labelRes = R.string.nav_settings,
            icon = NexoIcons.SettingsOutline,
            selectedIcon = NexoIcons.Settings,
        ),
    )

@Preview(showBackground = true, name = "Bottom nav — Dark")
@Composable
private fun NexoBottomNavBarDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoBottomNavBar(
            destinations = previewDestinations,
            currentRoute = NexoDestination.Home.route,
            onTabSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Bottom nav — Light")
@Composable
private fun NexoBottomNavBarLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoBottomNavBar(
            destinations = previewDestinations,
            currentRoute = NexoDestination.Highlights.route,
            onTabSelected = {},
        )
    }
}

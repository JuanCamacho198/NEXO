package com.nexo.ui.components.molecules

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
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
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

data class BottomNavItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * Bottom navigation bar with icon+label tabs and a Material 3 active-tab pill.
 *
 * Equal-width tabs spread across the available width. The active tab shows an
 * animated pill behind its icon, a scaled-up icon, and a tinted
 * icon+label; inactive tabs show a smaller icon in the secondary tint.
 *
 * **Theme**: all colors come from [MaterialTheme.colorScheme] (surfaceContainer,
 * primary, onSurfaceVariant, surfaceVariant), so the bar responds to
 * light/dark theme switches automatically. It does NOT read the legacy
 * `NexoColors` object (which only holds fixed dark tokens). The bar
 * separates from content by tonal contrast (`surfaceContainer` vs
 * `background`) with no shadow or divider, so no edge artifacts appear in
 * dark mode. The active tab's label renders in `FontWeight.SemiBold` while
 * inactive labels use `FontWeight.Medium`, so the selected tab reads as
 * emphasized.
 *
 * **Behavior**: tap a tab → [onTabSelected] with its `route`. Each tab is
 * [selectable] with `Role.Tab` for accessibility and uses the local ripple
 * indication clipped to a rounded shape, so the press feedback stays
 * contained instead of flooding the whole row.
 *
 * @param destinations Ordered list of tabs to render. Each item is a
 *   [BottomNavItem] with `route`, `labelRes`, and `icon`.
 * @param currentRoute Currently active route, or `null` if no tab is
 *   active. Matched against each `dest.route` for the active state.
 * @param onTabSelected Invoked with the selected tab's `route` when
 *   the user taps any tab.
 * @param modifier Modifier applied to the outer `Surface`.
 */
@Composable
fun NexoBottomNavBar(
    destinations: List<BottomNavItem>,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
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
 * A single selectable tab. The active state animates the pill background,
 * the icon/label tint, and the icon scale so switching tabs reads as a
 * smooth transition instead of a hard color flip.
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

    val iconTint by animateColorAsState(
        targetValue = if (isSelected) activeTint else inactiveTint,
        label = "navIconTint",
    )
    val pillColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        label = "navPillColor",
    )
    val pillPadding by animateDpAsState(
        targetValue = if (isSelected) 16.dp else 6.dp,
        label = "navPillPadding",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1f,
        label = "navIconScale",
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
                ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(pillColor)
                    .padding(horizontal = pillPadding, vertical = 4.dp),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = stringResource(id = item.labelRes),
                tint = iconTint,
                modifier =
                    Modifier
                        .size(NexoDimens.iconNavBar)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(id = item.labelRes),
            fontSize = 12.sp,
            color = iconTint,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private val previewDestinations =
    listOf(
        BottomNavItem(
            route = NexoDestination.Home.route,
            labelRes = R.string.nav_home,
            icon = NexoIcons.Home,
        ),
        BottomNavItem(
            route = NexoDestination.Library.route,
            labelRes = R.string.nav_library,
            icon = NexoIcons.Library,
        ),
        BottomNavItem(
            route = NexoDestination.Highlights.route,
            labelRes = R.string.nav_highlights,
            icon = NexoIcons.Highlights,
        ),
        BottomNavItem(
            route = NexoDestination.Settings.route,
            labelRes = R.string.nav_settings,
            icon = NexoIcons.Settings,
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
            currentRoute = NexoDestination.Home.route,
            onTabSelected = {},
        )
    }
}

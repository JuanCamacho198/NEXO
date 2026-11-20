package com.nexo.ui.components.molecules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Scaffold + `TopAppBar` shell for any settings sub-page. Provides a
 * back button, a bold title, and a 24dp-padded content column. Use
 * this as the outermost composable of any settings screen pushed
 * onto the navigation stack.
 *
 * @param title Sub-page title rendered in the `TopAppBar` as
 *   `titleLarge` bold.
 * @param onBack Invoked when the user taps the back arrow in the
 *   top-left. Typically pops the navigation back stack.
 * @param backContentDescription Accessibility label for the back
 *   arrow. Passed in by the caller because :core:ui declares no
 *   string resources — the consuming module resolves it.
 * @param modifier Modifier applied to the outer `Scaffold`.
 * @param content Composable body of the sub-page, rendered in a
 *   `Column` with 24dp horizontal padding below the `TopAppBar`.
 *   Receives a `ColumnScope`.
 *
 * **Visual**: standard Material 3 `TopAppBar` with an auto-mirrored
 *   back arrow and a bold title. Content area is a `Column` with
 *   `innerPadding` from the scaffold applied + 24dp horizontal
 *   padding.
 * **Behavior**: tap the back arrow → [onBack]. The Scaffold
 *   manages the status-bar inset for the top app bar; the caller
 *   does not need to handle insets manually for the content area
 *   thanks to `innerPadding`.
 * **Recomposition**: recomposes when `title` or `onBack` changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexoSettingsSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = nexoColors
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    Box(
                        modifier =
                            Modifier
                                .padding(start = 8.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.surface)
                                .clickable(onClickLabel = backContentDescription, onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = backContentDescription,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                // The bar sits on the page colour: the old header-tinted strip
                // made every sub-page look like a different screen from its body.
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.textPrimary,
                    ),
                // The host (SettingsScreenContent → outer Scaffold innerPadding)
                // already applies the status-bar inset to this NavHost. Letting
                // the TopAppBar consume it again created a ~24dp dead gap above
                // every settings sub-page toolbar.
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            )
        },
    ) { innerPadding ->
        val entered = remember { Animatable(0f) }
        LaunchedEffect(Unit) { entered.animateTo(1f, tween(SubPageEnterMs)) }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .graphicsLayer { alpha = entered.value }
                    .padding(horizontal = 20.dp)
                    .padding(top = NexoDimens.spacingSm),
        ) {
            content()
        }
    }
}

private const val SubPageEnterMs = 220

@Preview(showBackground = true)
@Composable
private fun SettingsSubPageDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoSettingsSubPage(
            title = "Settings",
            onBack = {},
        ) {
            Text(
                text = "Sample setting row",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSubPageLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoSettingsSubPage(
            title = "Settings",
            onBack = {},
        ) {
            Text(
                text = "Sample setting row",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

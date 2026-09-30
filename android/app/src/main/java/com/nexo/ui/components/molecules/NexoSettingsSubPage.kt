package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

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
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                // The host (SettingsScreenContent → outer Scaffold innerPadding)
                // already applies the status-bar inset to this NavHost. Letting
                // the TopAppBar consume it again created a ~24dp dead gap above
                // every settings sub-page toolbar.
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
                    .padding(top = NexoDimens.spacingMd),
        ) {
            content()
        }
    }
}

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

package com.nexo.presentation.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.screen.HomeTags
import com.nexo.ui.components.molecules.NexoChip
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoColors
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

/** One genre chip: its label, glyph, and the palette selectors that tint it. */
private data class HomeGenre(
    val labelRes: Int,
    val icon: ImageVector,
    val palette: NexoColors.() -> Pair<Color, Color>,
)

/**
 * The genre chips of the Home, in taxonomy order.
 *
 * Membership and order mirror `desktop/src/lib/features/discover/discoverChips.ts`
 * (`CHIP_TAXONOMY`) so a genre means the same thing on Home and Discover.
 *
 * Each chip carries its own tinted container and accent glyph, matching the
 * `.pen` `genre_chips` row: the colour is what separates a genre at a glance,
 * and the tinted background replaces the plain surface fill that made the row
 * read as disabled controls.
 *
 * Colours are held as [NexoColors] selectors, not resolved values: this list is
 * a file-level property, so it must not capture a palette at class-init time.
 * [HomeGenresSection] resolves them against the active `nexoColors`.
 */
private val HomeGenreChips: List<HomeGenre>
    get() =
        listOf(
            HomeGenre(R.string.home_genre_fiction, NexoIcons.BookOpen) { backgroundYellowTransparent to accentOrange },
            HomeGenre(R.string.home_genre_classics, NexoIcons.AutoStories) { backgroundBlueTransparent to accentBlue },
            HomeGenre(R.string.home_genre_adventure, NexoIcons.Explore) { backgroundGreenTransparent to accentGreen },
            HomeGenre(R.string.home_genre_mystery, NexoIcons.Search) { backgroundPurpleTransparent to accentPurple },
            HomeGenre(R.string.home_genre_romance, NexoIcons.Favorite) { backgroundYellowTransparent to accentOrange },
            HomeGenre(R.string.home_genre_science_fiction, NexoIcons.AutoAwesome) { backgroundPurpleTransparent to accentPurple },
            HomeGenre(R.string.home_genre_history, NexoIcons.Book) { backgroundBlueTransparent to accentBlue },
        )

/**
 * "Genres you may like" header plus its horizontally scrolling chip row.
 *
 * @param selectedGenreLabel Label of the active genre, or null for none. The
 *   selection is hoisted — this component never holds chip state.
 * @param onGenreSelected Invoked with the tapped genre's label.
 * @param onBrowseAll Invoked by the header chevron.
 */
@Composable
fun HomeGenresSection(
    modifier: Modifier = Modifier,
    selectedGenreLabel: String? = null,
    onGenreSelected: (String) -> Unit = {},
    onBrowseAll: () -> Unit = {},
) {
    val colors = nexoColors
    Column(modifier = modifier.fillMaxWidth().testTag(HomeTags.GENRES_SECTION)) {
        GenresHeader(onBrowseAll = onBrowseAll)
        Spacer(modifier = Modifier.height(NexoDimens.spacingMd))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
            items(HomeGenreChips) { genre ->
                val label = stringResource(genre.labelRes)
                val (container, accent) = genre.palette(colors)
                NexoChip(
                    label = label,
                    selected = label == selectedGenreLabel,
                    onClick = { onGenreSelected(label) },
                    icon = genre.icon,
                    containerColor = container,
                    iconTint = accent,
                    labelColor = colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun GenresHeader(onBrowseAll: () -> Unit) {
    val colors = nexoColors
    val contentDescription = stringResource(R.string.home_genres_content_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = contentDescription, onClick = onBrowseAll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.home_genres_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Icon(
            imageVector = NexoIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.textSecondary,
        )
    }
}

package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nexo.debug.SentryMetrics
import com.nexo.domain.model.CatalogBook
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.nexoColors

/**
 * Remote cover with the shared typographic fallback used by the results grid and
 * the IDLE rails.
 *
 * Branches: the real Coil cover when [coverUrl] is present and loading succeeded,
 * otherwise a [TypographicCover]. A failed load degrades to that cover instead of
 * leaving a broken image box.
 *
 * @param surface Funnel attribution for discover_cover_fail (rail|grid|detail).
 * @param letterSize Scale of the fallback; the title is set at a fixed share of it,
 *   so larger surfaces get a larger title.
 * @param author Shown on the fallback cover under the title, when known.
 */
@Composable
internal fun DiscoverBookCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    letterSize: TextUnit = 32.sp,
    surface: String = "grid",
    author: String? = null,
) {
    val colors = nexoColors
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }
    val showCover = !coverUrl.isNullOrBlank() && !coverFailed

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (showCover) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                // Solid painters, not painterResource: the cover_* XML drawables are
                // <shape>s, which crash Compose's vector/raster-only resource loader
                // (IllegalArgumentException). Surface matches the card background.
                placeholder = ColorPainter(colors.surface),
                error = ColorPainter(colors.surface),
                onError = {
                    coverFailed = true
                    SentryMetrics.count("discover_cover_fail", mapOf("surface" to surface))
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TypographicCover(title = title, author = author, letterSize = letterSize)
        }
    }
}

/**
 * Fallback cover for a book without (or with a broken) cover image: a tinted
 * gradient picked from the title, the title itself set in type, the author and
 * a short accent rule — so a shelf of coverless books reads as a row of designed
 * covers instead of identical grey boxes with one letter each.
 *
 * The tint is derived from the title's hash, so the same book always gets the
 * same colour. Every colour comes from [nexoColors].
 */
@Composable
private fun TypographicCover(
    title: String,
    author: String?,
    letterSize: TextUnit,
) {
    val colors = nexoColors
    val palettes =
        listOf(
            colors.accentBlue,
            colors.accentPurple,
            colors.accentGreen,
            colors.accentOrange,
        )
    val accent = palettes[(title.hashCode() and Int.MAX_VALUE) % palettes.size]
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = CoverTintTop), colors.surface),
                    ),
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = (letterSize.value * TitleScale).sp, lineHeight = (letterSize.value * TitleScale * 1.2f).sp),
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                )
            }
        }
    }
}

/** Opacity of the accent at the top of a typographic cover. */
private const val CoverTintTop = 0.38f

/** Title size relative to the surface's letter size (32sp rail → 14sp title). */
private const val TitleScale = 0.44f

/** First glyph of the title, uppercased; `?` for an empty title (never a blank box). */
internal fun bookInitial(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"

/** Fixed results-grid cell: cover + title (`text_primary`) + author (`text_secondary`). */
@Composable
fun DiscoverCard(
    book: CatalogBook,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    attributionNames: Map<String, String> = emptyMap(),
) {
    val colors = nexoColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .pressScaleClickable(shape = RoundedCornerShape(8.dp)) { onOpen(book.id) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DiscoverBookCover(
            coverUrl = book.coverUrl,
            title = book.title,
            author = book.authors.firstOrNull(),
            surface = "grid",
            letterSize = 36.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(GRID_COVER_HEIGHT),
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = colors.textPrimary,
        )
        Text(
            text = book.authors.joinToString(", "),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colors.textSecondary,
        )
        SourceAttributionBadge(
            provider = book.provider,
            attributionNames = attributionNames,
        )
        AccessBadge(book = book)
    }
}

/** Cover area height shared by the results grid and the section list. */
internal val GRID_COVER_HEIGHT = 160.dp

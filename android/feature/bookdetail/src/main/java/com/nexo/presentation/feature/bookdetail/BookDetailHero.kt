package com.nexo.presentation.feature.bookdetail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Book
import com.nexo.feature.bookdetail.R
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.StarRating
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

private val CoverWidth = 148.dp
private val CoverHeight = 222.dp
private val CoverRadius = 14.dp
private val CtaHeight = 52.dp

/** Height of the blurred-cover backdrop behind the hero. */
private val BackdropHeight = 360.dp
private const val BackdropAlpha = 0.55f

/** The cover rises in from slightly smaller, once per visit to this book. */
private const val CoverStartScale = 0.9f
private const val CoverEnterMs = 520

/**
 * Book detail hero: the book's own cover, blurred, as the backdrop; the cover
 * itself centred and large; title, author and rating centred below it; then the
 * single primary action.
 *
 * The backdrop replaces a flat `primary`→`surface` band that ended in a hard edge
 * against the page and clashed with the solid top bar above it.
 *
 * @param topInset Space reserved for the transparent top bar drawn over the hero.
 */
@Composable
internal fun BookHeroSection(
    book: Book,
    rating: Int?,
    onRatingChanged: (Int?) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    topInset: Dp = 0.dp,
) {
    val colors = nexoColors
    Box(modifier = Modifier.fillMaxWidth()) {
        HeroBackdrop(coverPath = book.coverPath)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = topInset + 8.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedCover(book = book)
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = book.author ?: stringResource(R.string.library_author_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(16.dp))
            RatingRow(rating = rating, onRatingChanged = onRatingChanged)
            Spacer(modifier = Modifier.height(24.dp))
            NexoButton(
                onClick = { onContinueReading(book.id, book.filePath, book.format) },
                variant = NexoButtonVariant.FILLED,
                shape = RoundedCornerShape(NexoDimens.cardCornerRadius + 4.dp),
                // Reading actions wear the orange reading accent, as Home's play button does.
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.accentOrange,
                        contentColor = colors.onAccentOrange,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(CtaHeight),
            ) {
                Icon(
                    imageVector = NexoIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (book.progressPercentage > 0f) {
                            stringResource(R.string.book_detail_continue_reading)
                        } else {
                            stringResource(R.string.book_detail_start_reading)
                        },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The cover, blurred and dimmed, fading into the page colour at the bottom so the
 * hero has no edge. `blur` is a no-op below Android 12; there the gradient alone
 * carries the backdrop, which still reads correctly.
 */
@Composable
private fun HeroBackdrop(coverPath: String?) {
    val colors = nexoColors
    Box(modifier = Modifier.fillMaxWidth().height(BackdropHeight)) {
        CoverThumbnail(
            coverPath = coverPath,
            contentDescription = null,
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = BackdropAlpha }
                    .blur(48.dp),
        )
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to colors.background.copy(alpha = 0.35f),
                                    0.6f to colors.background.copy(alpha = 0.8f),
                                    1f to colors.background,
                                ),
                        ),
                    ),
        )
    }
}

@Composable
private fun AnimatedCover(book: Book) {
    val colors = nexoColors
    var entered by rememberSaveable(book.id) { mutableStateOf(false) }
    val progress = remember(book.id) { Animatable(if (entered) 1f else 0f) }
    LaunchedEffect(book.id) {
        if (!entered) {
            progress.animateTo(1f, tween(CoverEnterMs, easing = FastOutSlowInEasing))
            entered = true
        }
    }
    val shape = RoundedCornerShape(CoverRadius)
    CoverThumbnail(
        coverPath = book.coverPath,
        contentDescription = stringResource(R.string.library_cover_content_description),
        modifier =
            Modifier
                .width(CoverWidth)
                .height(CoverHeight)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p
                    val scale = CoverStartScale + (1f - CoverStartScale) * p
                    scaleX = scale
                    scaleY = scale
                }.shadow(elevation = 18.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .border(1.dp, colors.borderSubtle, shape),
    )
}

@Composable
private fun RatingRow(
    rating: Int?,
    onRatingChanged: (Int?) -> Unit,
) {
    val colors = nexoColors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StarRating(
                rating = rating,
                onRatingChanged = onRatingChanged,
                fullStarContentDescriptionFormat = stringResource(R.string.book_detail_rating_star_full),
                halfStarContentDescriptionFormat = stringResource(R.string.book_detail_rating_star_half),
            )
            if (rating != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surface,
                    contentColor = colors.textPrimary,
                ) {
                    Text(
                        text = stringResource(R.string.book_detail_rating_value, rating / 2.0),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.book_detail_my_rating),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
    }
}

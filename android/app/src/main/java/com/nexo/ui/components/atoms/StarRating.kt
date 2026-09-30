package com.nexo.ui.components.atoms

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.AccentYellow
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Fill state of a single star in [StarRating] (design D1, REQ-detail-screen-7).
 */
enum class StarFill { EMPTY, HALF, FULL }

/**
 * Resolves how star [starIndex] (1..5) renders for a rating expressed in
 * half-units 0..10 (null = unrated): `rating / 2` full stars, plus one half
 * star when `rating % 2 == 1` (REQ-detail-screen-7).
 *
 * Examples: 9 → stars 1-4 FULL + star 5 HALF; 8 → stars 1-4 FULL, star 5 EMPTY;
 * null → all EMPTY.
 */
internal fun starFillAt(
    rating: Int?,
    starIndex: Int,
): StarFill {
    val value = rating ?: return StarFill.EMPTY
    val fullStars = value / 2
    return when {
        starIndex <= fullStars -> StarFill.FULL
        value % 2 == 1 && starIndex == fullStars + 1 -> StarFill.HALF
        else -> StarFill.EMPTY
    }
}

/**
 * Converts a tap position on star [starIndex] to a half-unit rating value
 * (SCEN-rating-half): tapping the LEFT half of a star selects the previous
 * whole-star value (`2 * (i - 1)`), tapping the RIGHT half selects that
 * star's half value (`2 * i - 1`).
 *
 * Examples: left half of star 5 → 8 (4.0); right half of star 5 → 9 (4.5);
 * left half of star 1 → 0.
 */
internal fun ratingValueFromTap(
    starIndex: Int,
    isLeftHalf: Boolean,
): Int = if (isLeftHalf) (starIndex - 1) * 2 else starIndex * 2 - 1

/** Converts half-units to the displayed 0.0..5.0 value (`rating / 2.0`); null stays null. */
internal fun ratingDisplayValue(rating: Int?): Double? = rating?.let { it / 2.0 }

/**
 * Five-star rating widget with half-star precision (design b3LCZx).
 *
 * [rating] is stored as half-units 0..10 (null = unrated); the widget renders
 * `rating / 2` filled stars plus a half star when `rating % 2 == 1` and shows
 * the rest as outlines. When [onRatingChanged] is null the widget is
 * display-only; otherwise each star is tappable in halves: the left half sets
 * `2 * i - 2`, the right half sets `2 * i - 1` (SCEN-rating-half).
 *
 * @param rating Current rating in half-units (0..10) or null when unrated.
 * @param onRatingChanged Emits the new half-unit value; null for display-only.
 * @param size Side length of each star.
 */
@Composable
fun StarRating(
    rating: Int?,
    onRatingChanged: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (1..5).forEach { starIndex ->
            val fill = starFillAt(rating, starIndex)
            val contentDescription =
                when (fill) {
                    StarFill.FULL -> stringResource(R.string.book_detail_rating_star_full, starIndex)
                    StarFill.HALF -> stringResource(R.string.book_detail_rating_star_half, starIndex - 0.5f)
                    StarFill.EMPTY -> null
                }
            val tapModifier =
                onRatingChanged?.let { callback ->
                    Modifier.pointerInput(starIndex, size) {
                        detectTapGestures { offset ->
                            callback(ratingValueFromTap(starIndex, offset.x < size.toPx() / 2f))
                        }
                    }
                } ?: Modifier

            Box(
                modifier =
                    Modifier
                        .size(size)
                        .then(tapModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        when (fill) {
                            StarFill.FULL -> NexoIcons.Star
                            StarFill.HALF -> NexoIcons.StarHalf
                            StarFill.EMPTY -> NexoIcons.StarBorder
                        },
                    contentDescription = contentDescription,
                    tint = if (fill == StarFill.EMPTY) MaterialTheme.colorScheme.outline else AccentYellow,
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun StarRatingDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StarRating(rating = 9, onRatingChanged = {})
            StarRating(rating = 8, onRatingChanged = {})
            StarRating(rating = null, onRatingChanged = {})
            StarRating(rating = 10)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StarRatingLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StarRating(rating = 9, onRatingChanged = {})
            StarRating(rating = null, onRatingChanged = {})
        }
    }
}

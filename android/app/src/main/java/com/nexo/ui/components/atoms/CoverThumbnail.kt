package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.nexo.R
import com.nexo.debug.DebugLog
import com.nexo.presentation.theme.NexoTheme

/**
 * Small cover thumbnail for the library grid. Loads from a local
 * file path or a remote URL via Coil with an explicit 80×120 px
 * decode size and reports the loader state through [onImageState].
 * Sibling of [NexoBookCover], tuned for the in-library list
 * view.
 *
 * @param coverPath Absolute path to the cover image file on local
 *   storage or a remote URL string. When `null` or blank, the
 *   request resolves to `fallback(R.drawable.cover_placeholder)`.
 * @param modifier Modifier applied to the `AsyncImage`. Use it to set
 *   the rendered size — the decode size is fixed at 80×120 px and is
 *   decoupled from the render size.
 * @param onImageState Optional observer invoked on every state change
 *   of the underlying `AsyncImagePainter` (`Loading`, `Success`,
 *   `Error`, `Empty`). Useful for shimmer/placeholder swap-in logic.
 *
 * **Visual**: `AsyncImage` with `ContentScale.Crop`, clipped to
 * `MaterialTheme.shapes.small`. `crossfade(true)` is enabled.
 * **Behavior**: pure rendering. The `ImageRequest` is `remember`-ed
 * keyed on `coverPath`/`(context, density, coverPath)` to avoid
 * rebuilding the request on unrelated recompositions. Both memory
 * and disk cache policies are enabled.
 * **Recomposition**: recomposes when `coverPath` or `modifier`
 * change; [onImageState] is invoked from `AsyncImage`'s internal
 * `State`, not composition.
 */
@Composable
fun CoverThumbnail(
    coverPath: String?,
    modifier: Modifier = Modifier,
    onImageState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageRequest =
        remember(context, density, coverPath) {
            ImageRequest
                .Builder(context)
                .data(coverPath?.takeIf { it.isNotBlank() })
                .size(
                    width = with(density) { 80.dp.toPx().toInt() },
                    height = with(density) { 120.dp.toPx().toInt() },
                ).placeholder(R.drawable.cover_placeholder)
                .error(R.drawable.cover_error)
                .fallback(R.drawable.cover_placeholder)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build()
        }

    AsyncImage(
        model = imageRequest,
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                DebugLog.error(TAG, "CoverThumbnail: cover load failed for $coverPath")
            }
            onImageState?.invoke(state)
        },
        contentDescription = stringResource(R.string.library_cover_content_description),
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(MaterialTheme.shapes.small),
    )
}

private const val TAG = "CoverThumbnail"

@Preview(showBackground = true)
@Composable
private fun CoverThumbnailPlaceholderDarkPreview() {
    NexoTheme(darkTheme = true) {
        CoverThumbnail(
            coverPath = null,
            modifier = Modifier.size(80.dp, 120.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CoverThumbnailPlaceholderLightPreview() {
    NexoTheme(darkTheme = false) {
        CoverThumbnail(
            coverPath = null,
            modifier = Modifier.size(80.dp, 120.dp),
        )
    }
}

package com.nexo.ui.util

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.nexo.debug.DebugDual

/**
 * Resolve a drawable resource into a [Painter], degrading gracefully instead of
 * crashing when the resource is neither a `VectorDrawable` nor a raster asset
 * (PNG/JPG/WEBP).
 *
 * Compose's [painterResource] throws
 * `IllegalArgumentException("Only VectorDrawables and rasterized asset types are
 * supported ex. PNG, JPG, WEBP")` for `<shape>`, `<layer-list>`, `<selector>` and
 * adaptive-icon XMLs. That exception crashed the app on the main thread
 * (NEXTPAGE-ANDROID-2 / -6). This guard keeps the screen alive and records which
 * resource produced the bad asset so it can be fixed at the source.
 *
 * @param resId drawable resource id.
 * @param fallbackColor solid placeholder shown when the resource is unsupported.
 * @param source short caller label used to identify the bad asset in logs.
 */
@Composable
fun safePainterResource(
    @DrawableRes resId: Int,
    fallbackColor: Color,
    source: String = "unknown",
): Painter =
    resolvePainterOrFallback(
        loader = { painterResource(resId) },
        fallback = { ColorPainter(fallbackColor) },
        onUnsupported = { error ->
            DebugDual.w(
                TAG,
                "Unsupported drawable res=0x${resId.toString(16)} source=$source: ${error.message}",
            )
        },
    )

private const val TAG = "SafePainter"

/**
 * Pure guard shared by [safePainterResource] and its unit test: run [loader];
 * when it throws the Compose unsupported-resource [IllegalArgumentException],
 * record it via [onUnsupported] and return [fallback] instead of propagating.
 * Any other throwable still propagates so real failures are not masked.
 */
internal inline fun <T> resolvePainterOrFallback(
    loader: () -> T,
    fallback: () -> T,
    onUnsupported: (IllegalArgumentException) -> Unit,
): T =
    try {
        loader()
    } catch (e: IllegalArgumentException) {
        onUnsupported(e)
        fallback()
    }

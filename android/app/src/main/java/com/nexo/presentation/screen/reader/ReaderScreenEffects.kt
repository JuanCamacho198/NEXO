package com.nexo.presentation.screen.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nexo.R
import com.nexo.debug.DebugLog
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay

private const val FULLSCREEN_AUTOHIDE_MS = 3_000L
private const val TAG = "ReaderScreenEffects"

/**
 * Hosts 5 reader lifecycle effects + rememberUpdatedState bridge.
 * Keeps WindowInsetsController + auto-hide timer isolated from overlay/content hosts.
 */
@Composable
fun ReaderScreenEffects(
    isFullscreen: Boolean,
    selectedBookId: String,
    bookFilePath: String?,
    bookFormat: String,
    bookIdentitySource: String = "snapshot",
    lastInteractionAt: Long,
    currentChapterIndex: Int,
    currentPdfPage: Int,
    viewModel: ReaderViewModel,
    view: View,
    controlsVisible: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    onLastInteractionChange: (Long) -> Unit,
    onShowChrome: () -> Unit,
) {
    val context = LocalContext.current

    @Suppress("UNUSED_VARIABLE")
    val currentOnShowChrome by rememberUpdatedState(onShowChrome)

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.let { it.requestedOrientation = originalOrientation }
        }
    }

    DisposableEffect(isFullscreen) {
        if (isFullscreen) {
            hideSystemBars(view)
        } else {
            showSystemBars(view)
        }
        onDispose {
            showSystemBars(view)
        }
    }

    DisposableEffect(selectedBookId) {
        viewModel.lifecycleHolder.onReaderOpened()
        onDispose { viewModel.lifecycleHolder.onReaderPaused() }
    }

    LaunchedEffect(selectedBookId, bookFilePath, bookFormat) {
        val idBlank = selectedBookId.isBlank()
        val pathBlank = bookFilePath.isNullOrBlank()
        if (idBlank && pathBlank) {
            DebugLog.warn(TAG, "loadBook skipped: blank selection (source=$bookIdentitySource)")
            viewModel.lifecycleHolder.reportLoadError(context.getString(R.string.book_not_found))
        } else {
            DebugLog.info(TAG, "loadBook trigger bookId=$selectedBookId format=$bookFormat source=$bookIdentitySource")
            viewModel.loadBookWithFallback(selectedBookId, bookFilePath, bookFormat)
        }
    }

    LaunchedEffect(isFullscreen, lastInteractionAt) {
        if (!isFullscreen) return@LaunchedEffect
        val snapshot = lastInteractionAt
        delay(FULLSCREEN_AUTOHIDE_MS)
        if (lastInteractionAt == snapshot) {
            val currentFullscreen = isFullscreen
            if (currentFullscreen) {
                onControlsVisibleChange(false)
            }
        }
    }

    LaunchedEffect(currentChapterIndex, currentPdfPage) {
        if (isFullscreen) {
            onLastInteractionChange(SystemClock.elapsedRealtime())
        }
    }
}

/**
 * Hides the status and navigation bars while the reader is in immersive mode.
 *
 * `WindowInsetsController`, `WindowInsets.Type.systemBars()` and
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` are all API 30, so the controller path is
 * guarded and API 26-29 falls back to the legacy `systemUiVisibility` flags. The flags
 * chosen mirror what `WindowInsetsControllerCompat` does internally on those levels:
 * `Type.systemBars()` expands to [SYSTEM_UI_FLAG_FULLSCREEN] plus
 * [SYSTEM_UI_FLAG_HIDE_NAVIGATION]. The `LAYOUT` variants are added as well so the WebView
 * keeps laying out under the now-hidden bars instead of reflowing when they reappear.
 */
private fun hideSystemBars(view: View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.windowInsetsController?.let { controller ->
            controller.hide(
                android.view.WindowInsets.Type
                    .systemBars(),
            )
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        view.systemUiVisibility = view.systemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

/** Restores the status and navigation bars. Counterpart of [hideSystemBars]. */
private fun showSystemBars(view: View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.windowInsetsController?.let { controller ->
            controller.show(
                android.view.WindowInsets.Type
                    .systemBars(),
            )
        }
    } else {
        @Suppress("DEPRECATION")
        view.systemUiVisibility = view.systemUiVisibility and
            (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            ).inv()
    }
}

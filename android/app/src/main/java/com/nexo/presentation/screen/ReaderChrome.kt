package com.nexo.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexo.R
import com.nexo.debug.DebugPrefs
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Reader Design Colors ──────────────────────────────────────────
private val READER_BG = Color(0xFF0D1322)
private val HEADER_FG = Color(0xFFDDE2F8)

/** Duration (ms) of the header/footer show/hide animation. */
private const val CHROME_ANIM_MS = 300

/** Duration (ms) the bookmark icon stays filled after a press (pulse feedback). */
private const val BOOKMARK_PULSE_MS = 600L

// ── ReaderChrome: structural layout ────────────────────────────────

/**
 * Structural layout for the reader screen.
 * Renders the header, content slot (format-specific), footer,
 * and overlays (search, highlights, settings, sleep timer, etc.)
 * on top of the dark reader background.
 *
 * The reader is always in immersive (fullscreen) mode. The header and
 * footer are toggled by [controlsVisible] and animate in/out with a
 * slide + fade transition — they are never removed from the layout
 * abruptly.
 *
 * @param controlsVisible drives the animated show/hide of the header
 *   and footer. Auto-toggled by the caller (tap to show, inactivity to hide).
 * @param contentModifier Modifier applied to the content Box (useful for
 *   attaching tap / gesture handlers that observe content taps).
 */
@Composable
fun ReaderChrome(
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
    overlays: @Composable BoxScope.() -> Unit = {},
    controlsVisible: Boolean = true,
    contentModifier: Modifier = Modifier,
) {
    // Dual log chrome visibility changes without causing recomposition churn
    androidx.compose.runtime.LaunchedEffect(controlsVisible) {
        com.nexo.debug.DebugDual
            .log(
                com.nexo.debug.DebugEvent
                    .ChromeToggled(controlsVisible),
            )
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(READER_BG)
                .padding(contentPadding),
    ) {
        // Content fills entire Box with stable constraints — never resized by chrome
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .then(contentModifier),
        ) {
            content()
        }

        // Header overlay top
        AnimatedVisibility(
            visible = controlsVisible,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(1f),
            enter =
                slideInVertically(
                    animationSpec = tween(CHROME_ANIM_MS, easing = FastOutSlowInEasing),
                    initialOffsetY = { -it },
                ) + fadeIn(animationSpec = tween(CHROME_ANIM_MS)),
            exit =
                slideOutVertically(
                    animationSpec = tween(CHROME_ANIM_MS, easing = FastOutSlowInEasing),
                    targetOffsetY = { -it },
                ) + fadeOut(animationSpec = tween(CHROME_ANIM_MS)),
        ) {
            header()
        }

        // Footer overlay bottom — needs solid background to be visible over white WebView
        AnimatedVisibility(
            visible = controlsVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f),
            enter =
                slideInVertically(
                    animationSpec = tween(CHROME_ANIM_MS, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = tween(CHROME_ANIM_MS)),
            exit =
                slideOutVertically(
                    animationSpec = tween(CHROME_ANIM_MS, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = tween(CHROME_ANIM_MS)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(READER_BG),
            ) {
                footer()
            }
        }

        overlays()
    }
}

// ── Header Component ──────────────────────────────────────────────

@Composable
fun ReaderHeader(
    chapters: List<BookChapter>,
    onNavigateBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleHighlights: () -> Unit,
    onCreateBookmark: () -> Unit,
    onToggleSplitSettings: () -> Unit = {},
    onToggleToc: () -> Unit = {},
    onToggleDebugPanel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(READER_BG)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back button
        IconButton(
            onClick = onNavigateBack,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape),
        ) {
            Icon(
                imageVector = NexoIcons.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
                tint = HEADER_FG,
                modifier = Modifier.size(20.dp),
            )
        }

        // Action buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search
            HeaderActionButton(
                icon = NexoIcons.Search,
                contentDescription = stringResource(R.string.search_label),
                onClick = onToggleSearch,
            )

            // aA Typography
            HeaderActionButton(
                icon = NexoIcons.TextSize,
                contentDescription = stringResource(R.string.reader_typography),
                onClick = onToggleSplitSettings,
            )

            // Index / TOC — hidden for books with no chapter list
            if (chapters.isNotEmpty()) {
                HeaderActionButton(
                    icon = NexoIcons.ListBullets,
                    contentDescription = stringResource(R.string.reader_toc),
                    onClick = onToggleToc,
                )
            }

            // Highlights (Create / pencil icon) — kept for the user-facing
            // highlights sheet. Tapping it should never crash, but the
            // previous wiring also routed long-press on the title to a
            // debug-only force-menu helper that could throw when the
            // selection overlay was already open. Both call sites now go
            // through safe state-only toggles.
            HeaderActionButton(
                icon = NexoIcons.Pencil,
                contentDescription = stringResource(R.string.reader_highlights_button),
                onClick = onToggleHighlights,
            )

            // Bookmark — outline→filled pulse on press (visual feedback only;
            // bookmark creation itself stays in the ViewModel).
            var bookmarkFilled by remember { mutableStateOf(false) }
            val bookmarkScope = rememberCoroutineScope()
            HeaderActionButton(
                icon = NexoIcons.Bookmark,
                contentDescription = stringResource(R.string.reader_add_bookmark),
                onClick = {
                    bookmarkFilled = true
                    onCreateBookmark()
                    bookmarkScope.launch {
                        delay(BOOKMARK_PULSE_MS)
                        bookmarkFilled = false
                    }
                },
                animatedIcon = {
                    AnimatedContent(
                        targetState = bookmarkFilled,
                        transitionSpec = {
                            (fadeIn(tween(120)) + scaleIn(tween(120)))
                                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120)))
                        },
                        label = "bookmarkToggle",
                    ) { filled ->
                        Icon(
                            imageVector = if (filled) NexoIcons.BookmarkFilled else NexoIcons.Bookmark,
                            contentDescription = stringResource(R.string.reader_add_bookmark),
                            tint = HEADER_FG,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )

            // Debug panel toggle — gated on runtime DebugPrefs so it works
            // in release builds when the debug toggle is enabled in Settings.
            if (DebugPrefs.isEnabled(LocalContext.current)) {
                HeaderActionButton(
                    icon = NexoIcons.BugReport,
                    contentDescription = stringResource(R.string.debug_panel_title),
                    onClick = onToggleDebugPanel,
                )
            }
        }
    }
}

// ── Header Action Button ──────────────────────────────────────────

@Composable
fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    animatedIcon: (@Composable () -> Unit)? = null,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(36.dp)
                .clip(CircleShape),
    ) {
        if (animatedIcon != null) {
            animatedIcon()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = HEADER_FG,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Edge-tap zones (top + bottom 5% of the parent) that re-show the reader
 * chrome (header + footer). Tapping either edge calls [onShowChrome],
 * which the screen wires to set `controlsVisible = true` and reset the
 * auto-hide timer.
 *
 * The middle 90% of the parent has NO overlay, so the WebView or PDF
 * fragment receives touches natively — long-press triggers text
 * selection, drag scrolls the page, and link taps work without
 * interference from the chrome toggle. This replaces the previous
 * full-screen tap-to-toggle, which conflicted with text selection.
 *
 * Shared by ReadiumReaderContent (EPUB) and ReadiumPdfReaderContent
 * (PDF) so both readers behave consistently.
 */
@Composable
internal fun ChromeEdgeTapZones(
    onShowChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val edgeHeight = maxHeight * 0.05f
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(edgeHeight)
                    .align(Alignment.TopCenter)
                    .pointerInput(onShowChrome) {
                        detectTapGestures(onTap = { onShowChrome() })
                    },
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(edgeHeight)
                    .align(Alignment.BottomCenter)
                    .pointerInput(onShowChrome) {
                        detectTapGestures(onTap = { onShowChrome() })
                    },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReaderChromeDarkPreview() {
    // Reader chrome renders only over the always-dark reader surface
    NexoTheme(darkTheme = true) {
        ReaderChrome(
            contentPadding = PaddingValues(0.dp),
            header = {
                Text(
                    text = "NEXO Reader",
                    color = HEADER_FG,
                    style = MaterialTheme.typography.titleSmall,
                )
            },
            footer = {
                Text(
                    text = "42%",
                    color = HEADER_FG,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            content = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(READER_BG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Reader content",
                        color = HEADER_FG,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }
}

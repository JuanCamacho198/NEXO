package com.nexo.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

// ── Reader Design Colors ──────────────────────────────────────────
private val READER_BG = Color(0xFF0D1322)
private val HEADER_FG = Color(0xFFDDE2F8)

/** Duration (ms) of the header/footer show/hide animation. */
private const val CHROME_ANIM_MS = 300

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

/** Touch target of every header action; 44dp keeps six actions inside a 360dp phone. */
private val HEADER_BUTTON_SIZE = 44.dp

/** Glyph size inside [HEADER_BUTTON_SIZE]: large enough that the 1.8 outline stroke stays crisp. */
private val HEADER_ICON_SIZE = 22.dp

@Composable
fun ReaderHeader(
    chapters: List<BookChapter>,
    onNavigateBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleHighlights: () -> Unit,
    onOpenBookmarks: () -> Unit,
    isPageBookmarked: Boolean = false,
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
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderActionButton(
            icon = NexoIcons.ReaderBack,
            contentDescription = stringResource(R.string.reader_back),
            onClick = onNavigateBack,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderActionButton(
                icon = NexoIcons.ReaderSearch,
                contentDescription = stringResource(R.string.search_label),
                onClick = onToggleSearch,
            )

            HeaderActionButton(
                icon = NexoIcons.ReaderAppearance,
                contentDescription = stringResource(R.string.reader_appearance_title),
                onClick = onToggleSplitSettings,
            )

            // Index / TOC — hidden for books with no chapter list
            if (chapters.isNotEmpty()) {
                HeaderActionButton(
                    icon = NexoIcons.ReaderContents,
                    contentDescription = stringResource(R.string.reader_toc),
                    onClick = onToggleToc,
                )
            }

            HeaderActionButton(
                icon = NexoIcons.ReaderHighlighter,
                contentDescription = stringResource(R.string.reader_highlights_button),
                onClick = onToggleHighlights,
            )

            // Bookmarks: opens the bookmarks sheet. Filled in the ribbon's
            // orange while the current page has a bookmark.
            HeaderActionButton(
                icon = NexoIcons.ReaderBookmark,
                contentDescription = stringResource(R.string.reader_bookmarks_title),
                onClick = onOpenBookmarks,
                animatedIcon = {
                    AnimatedContent(
                        targetState = isPageBookmarked,
                        transitionSpec = {
                            (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.6f))
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "pageBookmarked",
                    ) { bookmarked ->
                        Icon(
                            imageVector = if (bookmarked) NexoIcons.ReaderBookmarkFilled else NexoIcons.ReaderBookmark,
                            contentDescription = stringResource(R.string.reader_bookmarks_title),
                            tint = if (bookmarked) RIBBON_ORANGE else HEADER_FG,
                            modifier = Modifier.size(HEADER_ICON_SIZE),
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
                .size(HEADER_BUTTON_SIZE)
                .clip(CircleShape),
    ) {
        if (animatedIcon != null) {
            animatedIcon()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = HEADER_FG,
                modifier = Modifier.size(HEADER_ICON_SIZE),
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

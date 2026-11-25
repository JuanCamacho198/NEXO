package com.nexo.ui.components.molecules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.components.molecules.reader.ReaderSheetColors

/** The two floating menus share the reader sheets' palette, so they read as part of the same reader. */
internal object SelectionMenuColors {
    val Surface = ReaderSheetColors.Surface
    val Outline = ReaderSheetColors.Outline
    val Content = ReaderSheetColors.TextPrimary
    val Destructive = Color(0xFFF87171)
}

private val MenuShape = RoundedCornerShape(26.dp)

/** Gap between the menu and the text, wide enough for the caret plus a little air. */
internal const val SELECTION_MENU_GAP_DP = 12

/**
 * Pill chrome for both menus. A hairline outline keeps the dark pill separate
 * from a dark page, where the shadow alone disappears.
 */
internal fun Modifier.selectionMenuPill(): Modifier =
    this
        .shadow(10.dp, MenuShape)
        .background(SelectionMenuColors.Surface, MenuShape)
        .border(1.dp, SelectionMenuColors.Outline, MenuShape)
        .padding(horizontal = 4.dp, vertical = 4.dp)

/**
 * A 150ms fade-and-grow, started when the menu enters composition. Read it
 * inside `graphicsLayer` so the animation runs without recomposing the menu.
 */
private val MenuEntranceStartScale = 0.94f
private val MenuEntranceGrow = 0.06f

@Composable
internal fun rememberMenuEntrance(): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(durationMillis = 150, easing = FastOutSlowInEasing)) }
    return progress
}

internal fun Modifier.menuEntrance(progress: Animatable<Float, AnimationVector1D>): Modifier =
    graphicsLayer {
        alpha = progress.value
        val scale = MenuEntranceStartScale + MenuEntranceGrow * progress.value
        scaleX = scale
        scaleY = scale
    }

@Composable
internal fun MenuAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = SelectionMenuColors.Content,
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun MenuDivider() {
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp)
                .width(1.dp)
                .height(22.dp)
                .background(SelectionMenuColors.Outline),
    )
}

/**
 * One highlight colour. The faint rim keeps blue and red visible against the
 * navy pill; [selected] adds a ring around the colour a highlight already has.
 */
@Composable
internal fun ColorSwatch(
    hex: String,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        val dot =
            Modifier
                .clip(CircleShape)
                .background(hexToColor(hex))
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .border(2.dp, SelectionMenuColors.Content, CircleShape)
                        .padding(4.dp)
                        .then(dot),
            )
        } else {
            Box(modifier = Modifier.size(24.dp).then(dot))
        }
    }
}

/** Opens the full colour picker: a spectrum ring, so it reads as "any colour" rather than a sixth preset. */
@Composable
internal fun MoreColorsButton(onClick: () -> Unit) {
    val description = stringResource(R.string.reader_highlight_more_colors)
    val spectrum =
        Brush.sweepGradient(
            listOf(
                Color(0xFFEF4444),
                Color(0xFFFACC15),
                Color(0xFF4ADE80),
                Color(0xFF3B82F6),
                Color(0xFFA855F7),
                Color(0xFFEF4444),
            ),
        )
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(22.dp).border(3.dp, spectrum, CircleShape))
    }
}

/**
 * The small triangle that points from the menu to the text. Drawn pointing down;
 * [AnchoredOverlayBox] flips it when the menu sits below the selection. Only the
 * two slanted edges get the outline, so the base merges into the pill.
 */
@Composable
internal fun SelectionCaret() {
    val entrance = rememberMenuEntrance()
    Canvas(
        modifier =
            Modifier
                .size(width = 16.dp, height = 8.dp)
                .graphicsLayer { alpha = entrance.value },
    ) {
        val tip = size.width / 2
        val fill =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(tip, size.height)
                lineTo(size.width, 0f)
                close()
            }
        drawPath(fill, SelectionMenuColors.Surface)
        val edges =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(tip, size.height)
                lineTo(size.width, 0f)
            }
        drawPath(edges, SelectionMenuColors.Outline, style = Stroke(width = 1.dp.toPx()))
    }
}

/** The five colours a highlight can take: the reader's own palette when set, else the defaults. */
internal fun highlightPalette(custom: List<String>?): List<String> = custom?.takeIf { it.size >= PALETTE_SIZE }?.take(PALETTE_SIZE) ?: HighlightColor.defaultHexList()

private const val PALETTE_SIZE = 5

/** "Highlight in yellow": the action a swatch performs, not just its colour. */
@Composable
internal fun swatchDescription(hex: String): String {
    val name =
        when (HighlightColor.fromHex(hex) ?: HighlightColor.YELLOW) {
            HighlightColor.YELLOW -> stringResource(R.string.highlight_filter_yellow)
            HighlightColor.GREEN -> stringResource(R.string.highlight_filter_green)
            HighlightColor.BLUE -> stringResource(R.string.highlight_filter_blue)
            HighlightColor.ORANGE -> stringResource(R.string.highlight_filter_orange)
            HighlightColor.RED -> stringResource(R.string.highlight_filter_red)
        }
    // Invariant lowercase: a locale-aware one would make the label depend on a
    // non-observable read, and Compose lint rejects that in a composable.
    return stringResource(R.string.reader_highlight_in_color, name.lowercase())
}

internal fun hexToColor(hex: String): Color =
    runCatching {
        val clean = hex.removePrefix("#")
        Color((if (clean.length == 6) "FF$clean" else clean).toLong(16))
    }.getOrDefault(Color.Gray)

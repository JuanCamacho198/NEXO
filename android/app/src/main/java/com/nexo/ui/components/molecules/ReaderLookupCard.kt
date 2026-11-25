package com.nexo.ui.components.molecules

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.icons.NexoIcons

/** Which lookup the card shows. Both read the same selection, so the reader can flip between them. */
enum class LookupMode { DEFINE, TRANSLATE }

private val CardShape = RoundedCornerShape(20.dp)

/** Selections past either limit read as a passage: quoted and clamped instead of set as a headword. */
private const val PASSAGE_MIN_CHARS = 32
private const val PASSAGE_MIN_WORDS = 5

internal fun isPassage(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.length > PASSAGE_MIN_CHARS || trimmed.split(Regex("""\s+""")).size >= PASSAGE_MIN_WORDS
}

/**
 * The shared frame of the Define and Translate cards: tabs to switch lookup,
 * a close button, the text being looked up, then [content].
 *
 * Same navy surface as the selection menus, so the caret drawn by
 * [AnchoredOverlayBox] merges into it. The card is at most 360dp wide and keeps
 * 12dp from the screen edges; a paragraph's translation needs the width that a
 * fixed 280dp card did not give it.
 *
 * The close button's content description is the panel's "Close" string: the
 * Maestro flows find and tap it by that text.
 */
@Composable
internal fun ReaderLookupCard(
    mode: LookupMode,
    sourceText: String,
    closeDescription: String,
    onModeChange: (LookupMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val entrance = rememberMenuEntrance()
    Column(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .menuEntrance(entrance)
                .shadow(12.dp, CardShape)
                .background(SelectionMenuColors.Surface, CardShape)
                .border(1.dp, SelectionMenuColors.Outline, CardShape)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LookupTabs(mode = mode, onModeChange = onModeChange)
            Spacer(Modifier.weight(1f))
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = closeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(NexoIcons.Close, contentDescription = null, tint = ReaderSheetColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Spacer(Modifier.height(10.dp))
            SourceText(sourceText)
            content()
        }
    }
}

@Composable
private fun LookupTabs(
    mode: LookupMode,
    onModeChange: (LookupMode) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(ReaderSheetColors.SurfaceRaised)
                .padding(3.dp),
    ) {
        LookupTab(
            label = stringResource(R.string.text_selection_define),
            icon = NexoIcons.ReaderDefine,
            selected = mode == LookupMode.DEFINE,
            onClick = { onModeChange(LookupMode.DEFINE) },
        )
        LookupTab(
            label = stringResource(R.string.text_selection_translate),
            icon = NexoIcons.ReaderTranslate,
            selected = mode == LookupMode.TRANSLATE,
            onClick = { onModeChange(LookupMode.TRANSLATE) },
        )
    }
}

@Composable
private fun LookupTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The inactive tab is a real choice, not disabled: it stays near full
    // contrast and only the pill behind the active one tells them apart.
    val tint = if (selected) ReaderSheetColors.TextPrimary else ReaderSheetColors.TextPrimary.copy(alpha = 0.72f)
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(if (selected) ReaderSheetColors.SurfaceSelected else Color.Transparent)
                .clickable(enabled = !selected, onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** A word is set as a headword; a passage is quoted and clamped so the result stays in view. */
@Composable
private fun SourceText(text: String) {
    val trimmed = text.trim()
    if (isPassage(trimmed)) {
        Text(
            text = stringResource(R.string.reader_lookup_quoted, trimmed),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = ReaderSheetColors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            text = trimmed,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
            color = ReaderSheetColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The scrolling part of a lookup card, capped by [definitionPanelMaxHeightPx]
 * of the room the overlay gives: the result scrolls, the tabs, the close button
 * and any footer stay put.
 */
@Composable
internal fun LookupBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = constraints.maxHeight.takeIf { it != Int.MAX_VALUE } ?: 0
        val capPx =
            definitionPanelMaxHeightPx(
                availableHeightPx = available,
                chromeHeightPx = with(density) { DEFINITION_CHROME_HEIGHT.roundToPx() },
                minHeightPx = with(density) { DEFINITION_BODY_MIN_HEIGHT.roundToPx() },
            )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = with(density) { capPx.toDp() })
                    .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** Three pulsing bars where the result will be; reads as "on its way" without a spinner's urgency. */
@Composable
internal fun LookupSkeleton(label: String) {
    val pulse by rememberInfiniteTransition(label = "lookupSkeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "lookupSkeletonAlpha",
    )
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(1f, 0.86f, 0.55f).forEach { fraction ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(ReaderSheetColors.SurfaceSelected.copy(alpha = pulse)),
            )
        }
    }
}

/** A state that is not a result: a small mark, then the message. */
@Composable
internal fun LookupMessage(
    message: String,
    isError: Boolean = false,
    detail: String? = null,
) {
    val tint = if (isError) SelectionMenuColors.Destructive else ReaderSheetColors.TextSecondary
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (isError) NexoIcons.ErrorOutline else NexoIcons.Info,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(top = 1.dp).size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = if (isError) tint else ReaderSheetColors.TextPrimary)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = ReaderSheetColors.TextSecondary)
            }
        }
    }
}

/** A quiet pill button for the card's secondary actions (copy, translate instead). */
@Composable
internal fun LookupPillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val container = if (emphasized) ReaderSheetColors.Accent else ReaderSheetColors.SurfaceRaised
    val content = if (emphasized) ReaderSheetColors.Surface else ReaderSheetColors.TextPrimary
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(container)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(PaddingValues(horizontal = 14.dp, vertical = 9.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

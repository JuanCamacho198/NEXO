package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.domain.model.ScrollMode
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.icons.NexoIcons

private val ControlShape = RoundedCornerShape(14.dp)
private val ControlHeight = 48.dp

@Composable
fun AppearanceSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = ReaderSheetColors.TextSecondary,
        modifier = modifier.padding(bottom = 10.dp),
    )
}

// ── Text size ──────────────────────────────────────────────────────

@Composable
fun TextSizeStepper(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = FontSizePreset.entries
    val index = settings.fontSize.ordinal
    val sizeState = stringResource(R.string.reader_appearance_size_state, index + 1, presets.size)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            label = stringResource(R.string.reader_appearance_size_glyph),
            fontSize = 14.sp,
            contentDescription = stringResource(R.string.reader_appearance_smaller),
            enabled = index > 0,
            onClick = { onSettingsChanged(settings.copy(fontSize = presets[index - 1])) },
        )
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .semantics { stateDescription = sizeState },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEachIndexed { i, _ ->
                val reached = i <= index
                Box(
                    modifier =
                        Modifier
                            .size(if (i == index) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (reached) ReaderSheetColors.Accent else ReaderSheetColors.Outline),
                )
            }
        }
        StepButton(
            label = stringResource(R.string.reader_appearance_size_glyph),
            fontSize = 22.sp,
            contentDescription = stringResource(R.string.reader_appearance_larger),
            enabled = index < presets.lastIndex,
            onClick = { onSettingsChanged(settings.copy(fontSize = presets[index + 1])) },
        )
    }
}

@Composable
private fun StepButton(
    label: String,
    fontSize: TextUnit,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = 64.dp, height = ControlHeight)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(ControlShape)
                .background(ReaderSheetColors.SurfaceRaised)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = ReaderSheetColors.TextPrimary,
        )
    }
}

// ── Theme ──────────────────────────────────────────────────────────

@Composable
fun ThemeSwatches(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val themes =
        listOf(
            ReaderTheme.LIGHT to stringResource(R.string.reader_theme_light),
            ReaderTheme.SEPIA to stringResource(R.string.reader_theme_sepia),
            ReaderTheme.DARK to stringResource(R.string.reader_theme_dark),
            ReaderTheme.OLED to stringResource(R.string.theme_oled),
        )
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        themes.forEach { (theme, label) ->
            val selected = settings.theme == theme
            val border by animateColorAsState(
                if (selected) ReaderSheetColors.Accent else ReaderSheetColors.Outline,
                label = "themeBorder",
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSettingsChanged(settings.copy(theme = theme)) },
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(ControlShape)
                            .background(hexColor(theme.bgHex))
                            .border(if (selected) 2.dp else 1.dp, border, ControlShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.reader_appearance_sample),
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Serif,
                        color = hexColor(theme.textHex),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) ReaderSheetColors.Accent else ReaderSheetColors.TextSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Font ───────────────────────────────────────────────────────────

@Composable
fun FontChoices(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current =
        when (settings.fontName) {
            ReaderSettings.FONT_ORIGINAL -> ReaderSettings.FONT_ORIGINAL
            ReaderSettings.FONT_SANS -> ReaderSettings.FONT_SANS
            else -> ReaderSettings.FONT_SERIF
        }
    SegmentedRow(modifier = modifier, height = 64.dp) {
        FontSegment(
            selected = current == ReaderSettings.FONT_ORIGINAL,
            label = stringResource(R.string.reader_font_original),
            onClick = { onSettingsChanged(settings.copy(fontName = ReaderSettings.FONT_ORIGINAL)) },
        ) { tint ->
            Icon(NexoIcons.BookOpen, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        FontSegment(
            selected = current == ReaderSettings.FONT_SERIF,
            label = stringResource(R.string.reader_font_serif),
            onClick = { onSettingsChanged(settings.copy(fontName = ReaderSettings.FONT_SERIF)) },
        ) { tint ->
            Text(stringResource(R.string.reader_appearance_sample), fontSize = 20.sp, fontFamily = FontFamily.Serif, color = tint)
        }
        FontSegment(
            selected = current == ReaderSettings.FONT_SANS,
            label = stringResource(R.string.reader_font_sans),
            onClick = { onSettingsChanged(settings.copy(fontName = ReaderSettings.FONT_SANS)) },
        ) { tint ->
            Text(stringResource(R.string.reader_appearance_sample), fontSize = 20.sp, fontFamily = FontFamily.SansSerif, color = tint)
        }
    }
}

@Composable
private fun RowScope.FontSegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    Segment(selected = selected, onClick = onClick) { tint ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { glyph(tint) }
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

// ── Reading mode ───────────────────────────────────────────────────

@Composable
fun ReadingModeChoices(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrolling = settings.scrollMode == ScrollMode.VERTICAL || settings.verticalScroll
    SegmentedRow(modifier = modifier) {
        TextSegment(
            selected = !scrolling,
            label = stringResource(R.string.reader_layout_pages),
            onClick = { onSettingsChanged(settings.copy(scrollMode = ScrollMode.PAGINATED, verticalScroll = false)) },
        )
        TextSegment(
            selected = scrolling,
            label = stringResource(R.string.reader_layout_scroll),
            onClick = { onSettingsChanged(settings.copy(scrollMode = ScrollMode.VERTICAL, verticalScroll = true)) },
        )
    }
}

// ── Layout group: publisher styles, spacing, alignment ─────────────

/**
 * Line spacing and alignment only take effect once the book's own styles are
 * off (the EPUB engine ignores them otherwise). Instead of disabling them, a
 * tap on either turns the book's style off for the reader, so no control is
 * ever dead; the switch shows what happened and undoes it.
 */
@Composable
fun TextLayoutGroup(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val custom = !settings.editorValues
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ReaderSheetColors.SurfaceRaised)
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(R.string.reader_appearance_publisher_styles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReaderSheetColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        stringResource(
                            if (custom) R.string.reader_appearance_publisher_off else R.string.reader_appearance_publisher_on,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderSheetColors.TextSecondary,
                )
            }
            Switch(
                checked = settings.editorValues,
                onCheckedChange = { onSettingsChanged(settings.copy(editorValues = it)) },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = ReaderSheetColors.Surface,
                        checkedTrackColor = ReaderSheetColors.Accent,
                        uncheckedThumbColor = ReaderSheetColors.TextSecondary,
                        uncheckedTrackColor = ReaderSheetColors.Surface,
                        uncheckedBorderColor = ReaderSheetColors.Outline,
                    ),
            )
        }

        Spacer(Modifier.height(16.dp))
        AppearanceSectionLabel(stringResource(R.string.reader_appearance_line_spacing))
        val spacingLabels =
            listOf(
                stringResource(R.string.reader_line_spacing_tight),
                stringResource(R.string.reader_line_spacing_normal),
                stringResource(R.string.reader_line_spacing_comfortable),
                stringResource(R.string.reader_line_spacing_wide),
            )
        SegmentedRow(
            containerColor = ReaderSheetColors.Surface,
            modifier = Modifier.alpha(if (custom) 1f else 0.55f),
        ) {
            LineHeightPreset.entries.forEachIndexed { i, preset ->
                Segment(
                    selected = custom && settings.lineHeight == preset,
                    contentDescription = spacingLabels[i],
                    onClick = { onSettingsChanged(settings.copy(lineHeight = preset, editorValues = false)) },
                ) { tint -> LineSpacingGlyph(gap = (2.5f + i * 1.5f).dp, tint = tint) }
            }
        }

        Spacer(Modifier.height(16.dp))
        AppearanceSectionLabel(stringResource(R.string.reader_appearance_alignment))
        val justified = settings.layoutPrefs.alignment == LayoutPreferences.Alignment.JUSTIFY
        SegmentedRow(
            containerColor = ReaderSheetColors.Surface,
            modifier = Modifier.alpha(if (custom) 1f else 0.55f),
        ) {
            listOf(
                Triple(LayoutPreferences.Alignment.LEFT, NexoIcons.AlignLeft, R.string.reader_align_left),
                Triple(LayoutPreferences.Alignment.JUSTIFY, NexoIcons.AlignJustify, R.string.reader_align_justify),
            ).forEach { (alignment, icon, labelRes) ->
                val selected = custom && (if (alignment == LayoutPreferences.Alignment.JUSTIFY) justified else !justified)
                Segment(
                    selected = selected,
                    onClick = {
                        onSettingsChanged(
                            settings.copy(
                                layoutPrefs = settings.layoutPrefs.copy(alignment = alignment),
                                editorValues = false,
                            ),
                        )
                    },
                ) { tint ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                }
            }
        }
    }
}

// ── Building blocks ────────────────────────────────────────────────

@Composable
private fun SegmentedRow(
    modifier: Modifier = Modifier,
    height: Dp = ControlHeight,
    containerColor: Color = ReaderSheetColors.SurfaceRaised,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(ControlShape)
                .background(containerColor)
                .padding(4.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun RowScope.TextSegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Segment(selected = selected, onClick = onClick) { tint ->
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

@Composable
private fun RowScope.Segment(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    content: @Composable (Color) -> Unit,
) {
    val background by animateColorAsState(
        if (selected) ReaderSheetColors.SurfaceSelected else Color.Transparent,
        label = "segmentBackground",
    )
    val tint by animateColorAsState(
        if (selected) ReaderSheetColors.Accent else ReaderSheetColors.TextPrimary,
        label = "segmentTint",
    )
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(11.dp))
                .background(background)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

@Composable
private fun LineSpacingGlyph(
    gap: Dp,
    tint: Color,
) {
    Canvas(modifier = Modifier.size(width = 22.dp, height = 20.dp)) {
        val stroke = 1.8.dp.toPx()
        val step = gap.toPx() + stroke
        val top = (size.height - step * 2) / 2
        repeat(3) { line ->
            val y = top + step * line
            drawLine(tint, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

private fun hexColor(hex: String): Color =
    runCatching {
        val clean = hex.removePrefix("#")
        Color((if (clean.length == 6) "FF$clean" else clean).toLong(16))
    }.getOrDefault(Color.Gray)

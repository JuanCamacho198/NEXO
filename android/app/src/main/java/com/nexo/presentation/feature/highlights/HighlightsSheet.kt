package com.nexo.presentation.feature.highlights

/**
 * Local color filter (String? via HighlightColor.name) is intentionally kept as
 * local `String?` state rather than the ViewModel's `Set<String>` (hex). This
 * dualism is documented: sheet filters by HighlightColor.name (enum name) while
 * the Highlights screen filters by hex Set. Do not lift sheet state into VM.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.feature.highlights.utils.ChapterLocation
import com.nexo.presentation.feature.highlights.utils.matchesHighlightFilter
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.components.molecules.reader.ReaderSheetHandle
import com.nexo.ui.components.molecules.reader.ReaderSheetShape
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.launch

/**
 * The current book's highlights, in the order they appear in the book and
 * grouped under their chapter. Colour filters list only the colours actually
 * used, each with its count. Tapping a highlight closes the sheet (with its
 * own animation) and then jumps to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsSheet(
    highlights: List<Highlight>,
    onHighlightSelected: (Highlight) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<ChapterLocation> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedColorFilter by remember { mutableStateOf<String?>(null) }

    val colorCounts =
        remember(highlights) {
            highlights
                .groupingBy { HighlightColor.fromHex(it.color) ?: HighlightColor.YELLOW }
                .eachCount()
        }
    val filtered =
        remember(highlights, selectedColorFilter) {
            highlights.filter { matchesHighlightFilter(it.color, selectedColorFilter) }
        }
    val groups = remember(filtered, chapters) { groupHighlightsByChapter(filtered, chapters) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ReaderSheetColors.Surface,
        shape = ReaderSheetShape,
        dragHandle = { ReaderSheetHandle() },
    ) {
        // A fixed height while there is content, so filtering never makes the
        // sheet jump; the empty state wraps.
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .then(if (highlights.isEmpty()) Modifier else Modifier.fillMaxHeight(0.8f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = stringResource(R.string.highlights_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ReaderSheetColors.TextPrimary,
                )
                if (highlights.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.reader_highlights_count, highlights.size, highlights.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderSheetColors.TextSecondary,
                    )
                }
            }

            if (highlights.isEmpty()) {
                HighlightsEmptyState(
                    title = stringResource(R.string.highlights_empty),
                    hint = stringResource(R.string.reader_highlights_empty_hint),
                )
                return@Column
            }

            if (colorCounts.size > 1) {
                Spacer(Modifier.height(14.dp))
                ColorFilterRow(
                    total = highlights.size,
                    colorCounts = colorCounts,
                    selected = selectedColorFilter,
                    onSelected = { selectedColorFilter = it },
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = ReaderSheetColors.Outline)

            if (filtered.isEmpty()) {
                HighlightsEmptyState(title = stringResource(R.string.reader_highlights_filter_empty), hint = null)
            } else {
                val elsewhere = stringResource(R.string.reader_highlights_no_chapter)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "chapter:${group.chapterTitle}") {
                            ChapterHeader(
                                title = group.chapterTitle ?: elsewhere,
                                count = group.highlights.size,
                            )
                        }
                        items(group.highlights, key = { it.id }) { highlight ->
                            HighlightRow(
                                highlight = highlight,
                                onClick = {
                                    scope
                                        .launch { sheetState.hide() }
                                        .invokeOnCompletion { onHighlightSelected(highlight) }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorFilterRow(
    total: Int,
    colorCounts: Map<HighlightColor, Int>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            selected = selected == null,
            label = stringResource(R.string.highlight_filter_all),
            count = total,
            dot = null,
            onClick = { onSelected(null) },
        )
        HighlightColor.entries.filter { it in colorCounts }.forEach { color ->
            FilterPill(
                selected = selected == color.name,
                label = colorLabel(color),
                count = colorCounts.getValue(color),
                dot = hexColor(color.hex),
                onClick = { onSelected(color.name) },
            )
        }
    }
}

/** Pill with an optional colour dot. Colour pills hide their name visually; it stays the accessible name (E2E flows tap it). */
@Composable
private fun FilterPill(
    selected: Boolean,
    label: String,
    count: Int,
    dot: Color?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier =
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (selected) ReaderSheetColors.SurfaceSelected else ReaderSheetColors.SurfaceRaised)
                .border(1.dp, if (selected) ReaderSheetColors.Accent else Color.Transparent, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) ReaderSheetColors.Accent else ReaderSheetColors.TextPrimary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) ReaderSheetColors.Accent else ReaderSheetColors.TextSecondary,
        )
    }
}

@Composable
private fun ChapterHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 10.dp)
                .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ReaderSheetColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = ReaderSheetColors.TextSecondary,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ReaderSheetColors.SurfaceRaised)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun HighlightRow(
    highlight: Highlight,
    onClick: () -> Unit,
) {
    // The colour actually used in the book (custom palettes included), not the
    // nearest canonical one that the filter groups it under.
    val markColor = hexColor(highlight.color, fallback = hexColor(HighlightColor.YELLOW.hex))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(ReaderSheetColors.SurfaceRaised)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(markColor),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text =
                    highlight.textContent
                        .replace("\\n", " ")
                        .replace("\n", " ")
                        .trim(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                color = ReaderSheetColors.TextPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val note = highlight.note?.takeIf { it.isNotBlank() }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ReaderSheetColors.Surface)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = NexoIcons.Pencil,
                        contentDescription = null,
                        tint = ReaderSheetColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderSheetColors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlightsEmptyState(
    title: String,
    hint: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(ReaderSheetColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NexoIcons.ReaderHighlighter,
                contentDescription = null,
                tint = ReaderSheetColors.Accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = ReaderSheetColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = ReaderSheetColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun colorLabel(color: HighlightColor): String =
    when (color) {
        HighlightColor.YELLOW -> stringResource(R.string.highlight_filter_yellow)
        HighlightColor.GREEN -> stringResource(R.string.highlight_filter_green)
        HighlightColor.ORANGE -> stringResource(R.string.highlight_filter_orange)
        HighlightColor.BLUE -> stringResource(R.string.highlight_filter_blue)
        HighlightColor.RED -> stringResource(R.string.highlight_filter_red)
    }

private fun hexColor(
    hex: String,
    fallback: Color = Color.Gray,
): Color =
    runCatching {
        val clean = hex.removePrefix("#").trim()
        require(clean.length == 6 || clean.length == 8)
        Color((if (clean.length == 6) "FF$clean" else clean).toLong(16))
    }.getOrDefault(fallback)

private val previewHighlights =
    listOf(
        Highlight(
            id = "h1",
            bookId = "book-1",
            cfiRange = "readium:ch1.xhtml",
            textContent = "Hoy ha muerto mamá. O quizá ayer. No lo sé.",
            note = "La frase de apertura más famosa del libro",
            color = HighlightColor.YELLOW.hex,
            updatedAtEpochMillis = 1_700_000_000_000L,
            deletedAtEpochMillis = null,
        ),
        Highlight(
            id = "h2",
            bookId = "book-1",
            cfiRange = "readium:ch1.xhtml",
            textContent = "El asilo de ancianos está en Marengo, a ochenta kilómetros de Argel.",
            note = null,
            color = HighlightColor.ORANGE.hex,
            updatedAtEpochMillis = 1_700_000_000_000L,
            deletedAtEpochMillis = null,
        ),
        Highlight(
            id = "h3",
            bookId = "book-1",
            cfiRange = "readium:ch2.xhtml",
            textContent = "Pensé que era un domingo de menos, que mamá ya estaba enterrada.",
            note = null,
            color = HighlightColor.BLUE.hex,
            updatedAtEpochMillis = 1_700_000_000_000L,
            deletedAtEpochMillis = null,
        ),
    )

private val previewChapters =
    listOf(
        ChapterLocation(index = 0, title = "I", href = "ch1.xhtml"),
        ChapterLocation(index = 1, title = "II", href = "ch2.xhtml"),
    )

@Preview(showBackground = true)
@Composable
private fun HighlightsSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        HighlightsSheet(
            highlights = previewHighlights,
            onHighlightSelected = {},
            onDismiss = {},
            chapters = previewChapters,
        )
    }
}

// Preview-only: fixed dark palette — the reader sheets are always dark.
@Preview(showBackground = true)
@Composable
private fun HighlightsSheetEmptyPreview() {
    NexoTheme(darkTheme = false) {
        HighlightsSheet(
            highlights = emptyList(),
            onHighlightSelected = {},
            onDismiss = {},
        )
    }
}

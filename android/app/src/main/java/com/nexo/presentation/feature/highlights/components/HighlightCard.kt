package com.nexo.presentation.feature.highlights.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.feature.highlights.utils.ChapterLocation
import com.nexo.presentation.feature.highlights.utils.HighlightLocationResolver
import com.nexo.presentation.feature.highlights.utils.parseColorHex

@Composable
fun HighlightCard(
    highlight: Highlight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<ChapterLocation> = emptyList(),
) {
    val colorHex =
        highlight.color.let { colorStr ->
            HighlightColor.fromHex(colorStr)?.hex ?: HighlightColor.YELLOW.hex
        }

    val locationLabel = rememberLocationLabel(highlight, chapters)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(4.dp, 48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(parseColorHex(colorHex)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlight.textContent.replace("\\n", " ").replace("\n", " "),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFDDE2F8),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (locationLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = locationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF718096),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val note = highlight.note?.takeIf { it.isNotBlank() }
            if (note != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note.take(60),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF718096),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Affordance: the row is tappable, so it must look tappable.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF718096),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Builds the location label shown under the excerpt, or `null` when the
 * highlight carries no derivable position.
 *
 * A highlight can point at a resource that has no TOC entry — `sinopsis.xhtml`
 * is in this book's reading order but absent from `toc.ncx` — so a chapter
 * match is attempted first and a whole-book progression fallback second.
 */
@Composable
private fun rememberLocationLabel(
    highlight: Highlight,
    chapters: List<ChapterLocation>,
): String? {
    val page = HighlightLocationResolver.pdfPageNumber(highlight.cfiRange)
    if (page != null) {
        return stringResource(R.string.highlight_pdf_page, page + 1)
    }
    val title = HighlightLocationResolver.chapterTitle(highlight.cfiRange, chapters)
    if (title != null) {
        return title
    }
    val percent =
        HighlightLocationResolver.progressionLabel(
            HighlightLocationResolver.locatorProgression(highlight.locatorJson),
        )
    return percent?.let { stringResource(R.string.highlight_reading_progress, it) }
}

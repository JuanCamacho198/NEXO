package com.nexo.presentation.feature.highlights

/**
 * Local color filter (String? via HighlightColor.name) is intentionally kept as
 * local `String?` state rather than the ViewModel's `Set<String>` (hex). This
 * dualism is documented: sheet filters by HighlightColor.name (enum name) while
 * the Highlights screen filters by hex Set. Do not lift sheet state into VM.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.presentation.feature.highlights.components.HighlightCard
import com.nexo.presentation.feature.highlights.components.HighlightsSheetFilterChips
import com.nexo.presentation.theme.NexoTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsSheet(
    highlights: List<Highlight>,
    onHighlightSelected: (Highlight) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedColorFilter by remember { mutableStateOf<String?>(null) }

    val colorFilterLabels =
        listOf(
            null to stringResource(R.string.highlight_filter_all),
        ) +
            HighlightColor.entries.map { color ->
                color.name to
                    when (color) {
                        HighlightColor.YELLOW -> stringResource(R.string.highlight_filter_yellow)
                        HighlightColor.GREEN -> stringResource(R.string.highlight_filter_green)
                        HighlightColor.ORANGE -> stringResource(R.string.highlight_filter_orange)
                        HighlightColor.BLUE -> stringResource(R.string.highlight_filter_blue)
                        HighlightColor.RED -> stringResource(R.string.highlight_filter_red)
                    }
            }

    val filteredHighlights =
        if (selectedColorFilter == null) {
            highlights
        } else {
            highlights.filter { it.color.equals(selectedColorFilter, ignoreCase = true) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161F33),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4A5568))
                        .align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.highlights_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFDDE2F8),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HighlightsSheetFilterChips(
                colorFilterLabels = colorFilterLabels,
                selectedColorFilter = selectedColorFilter,
                onFilterSelected = { selectedColorFilter = it },
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2F3445))
            Spacer(modifier = Modifier.height(8.dp))
            if (filteredHighlights.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.highlights_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF718096),
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredHighlights, key = { it.id }) { highlight ->
                        HighlightCard(
                            highlight = highlight,
                            onClick = { onHighlightSelected(highlight) },
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightsSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        HighlightsSheet(
            highlights =
                listOf(
                    Highlight(
                        id = "h1",
                        bookId = "book-1",
                        cfiRange = "epubcfi(/6/4[chap1]!/4[body]/2/1:10)",
                        textContent = "It was a bright cold day in April, and the clocks were striking thirteen.",
                        note = "Opening line",
                        color = HighlightColor.YELLOW.hex,
                        updatedAtEpochMillis = 1_700_000_000_000L,
                        deletedAtEpochMillis = null,
                    ),
                    Highlight(
                        id = "h2",
                        bookId = "book-1",
                        cfiRange = "epubcfi(/6/4[chap2]!/4[body]/2/1:5)",
                        textContent = "War is peace. Freedom is slavery. Ignorance is strength.",
                        note = null,
                        color = HighlightColor.BLUE.hex,
                        updatedAtEpochMillis = 1_700_000_000_000L,
                        deletedAtEpochMillis = null,
                    ),
                ),
            onHighlightSelected = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightsSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        HighlightsSheet(
            highlights =
                listOf(
                    Highlight(
                        id = "h1",
                        bookId = "book-1",
                        cfiRange = "epubcfi(/6/4[chap1]!/4[body]/2/1:10)",
                        textContent = "It was a bright cold day in April, and the clocks were striking thirteen.",
                        note = "Opening line",
                        color = HighlightColor.YELLOW.hex,
                        updatedAtEpochMillis = 1_700_000_000_000L,
                        deletedAtEpochMillis = null,
                    ),
                    Highlight(
                        id = "h2",
                        bookId = "book-1",
                        cfiRange = "epubcfi(/6/4[chap2]!/4[body]/2/1:5)",
                        textContent = "War is peace. Freedom is slavery. Ignorance is strength.",
                        note = null,
                        color = HighlightColor.BLUE.hex,
                        updatedAtEpochMillis = 1_700_000_000_000L,
                        deletedAtEpochMillis = null,
                    ),
                ),
            onHighlightSelected = {},
            onDismiss = {},
        )
    }
}

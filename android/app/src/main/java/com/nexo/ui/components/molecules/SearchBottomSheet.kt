package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.SearchResult
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Modal bottom sheet for full-text search inside the current book.
 * Owns no search state — the caller drives the query, results, and
 * the loading flag.
 *
 * Visual design is locked to the dark reader theme (background
 * `#161F33`, text `#DDE2F8`, accent `#ADC6FF`) — this sheet is
 * intended to be shown on top of the dark reading surface.
 *
 * Design: drag handle → search input (lupa icon + clear button)
 * → results count → results list with a per-chapter color marker
 * (5-color rotating palette based on `chapterIndex % 5`).
 *
 * @param query Current search query text. Hoisted by the parent.
 *   When non-empty, the clear (X) button is shown in the trailing
 *   icon slot and the results count line is displayed.
 * @param results Search results to display. When empty (and
 *   [isSearching] is `false` and [query] is non-empty) the "no
 *   results" placeholder is shown.
 * @param isSearching `true` while a search is in flight. Renders a
 *   centered `CircularProgressIndicator` in place of the results
 *   list.
 * @param onQueryChange Invoked on every keystroke in the search
 *   field. The caller is expected to debounce and re-query.
 * @param onClearQuery Invoked when the user taps the clear (X) icon.
 *   Typically clears the query in the parent's state.
 * @param onResultSelected Invoked with the tapped [SearchResult].
 *   The sheet does NOT auto-dismiss — the parent typically closes
 *   it after jumping to the result.
 * @param onDismiss Invoked on swipe-down, scrim-tap, or back-press.
 * @param modifier Modifier applied to the inner `Column`.
 *
 * **Visual**: dark `ModalBottomSheet` (24dp top corners). Drag
 *   handle, 12dp-rounded search input (`#ADC6FF` focused border,
 *   `#2F3445` unfocused, `#ADC6FF` cursor), 12dp gap, optional
 *   results count (`#718096`), then 320dp `LazyColumn` of result
 *   rows (4dp × 32dp color marker + 2-line text preview + chapter
 *   number).
 * **Behavior**: search input is a single-line `OutlinedTextField` with
 *   `ImeAction.Search` (no IME action wired — caller handles query
 *   lifecycle). The clear button only appears when [query] is
 *   non-empty. The three render branches (loading, empty, results)
 *   are mutually exclusive via `when`.
 * **Recomposition**: recomposes when any parameter changes.
 *   `LazyColumn` items are keyed by `"$chapterIndex-$offset"`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBottomSheet(
    query: String,
    results: List<SearchResult>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onResultSelected: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            // ── Drag Handle ────────────────────────────────────────
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

            // ── Search Input ───────────────────────────────────────
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_input_hint),
                        color = Color(0xFF718096),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = NexoIcons.Search,
                        contentDescription = null,
                        tint = Color(0xFF718096),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(
                                imageVector = NexoIcons.Close,
                                contentDescription = stringResource(R.string.search_clear),
                                tint = Color(0xFF718096),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFDDE2F8)),
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFADC6FF),
                        unfocusedBorderColor = Color(0xFF2F3445),
                        cursorColor = Color(0xFFADC6FF),
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Results Count ──────────────────────────────────────
            if (query.isNotEmpty() && !isSearching) {
                Text(
                    text = stringResource(R.string.search_results_count, results.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF718096),
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Content State ──────────────────────────────────────
            when {
                isSearching -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFADC6FF),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                query.isNotEmpty() && results.isEmpty() && !isSearching -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF718096),
                        )
                    }
                }

                else -> {
                    // ── Results List ───────────────────────────────
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val colorMarkers =
                            listOf(
                                HighlightColor.YELLOW.hex,
                                HighlightColor.GREEN.hex,
                                HighlightColor.BLUE.hex,
                                HighlightColor.ORANGE.hex,
                                HighlightColor.RED.hex,
                            )

                        items(results, key = { "${it.chapterIndex}-${it.offset}" }) { result ->
                            val markerColor = colorMarkers[result.chapterIndex % colorMarkers.size]

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onResultSelected(result) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Color marker
                                Box(
                                    modifier =
                                        Modifier
                                            .size(4.dp, 32.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(parseColorHex(markerColor)),
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFDDE2F8),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.search_result_chapter, result.chapterIndex + 1),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF718096),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseColorHex(hex: String): Color =
    try {
        val sanitized = hex.removePrefix("#")
        val longHex =
            when (sanitized.length) {
                6 -> "FF$sanitized"
                8 -> sanitized
                else -> "FF000000"
            }
        Color(longHex.toLong(16))
    } catch (_: Exception) {
        Color.Magenta
    }

@Preview(showBackground = true)
@Composable
private fun SearchBottomSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        SearchBottomSheet(
            query = "times",
            results =
                listOf(
                    SearchResult(
                        text = "It was the best of times, it was the worst of times.",
                        offset = 12,
                        chapterIndex = 0,
                        chapterTitle = "Book I — Chapter 1",
                    ),
                    SearchResult(
                        text = "In the ensuing silence, the word hung in the air.",
                        offset = 8,
                        chapterIndex = 1,
                        chapterTitle = "Book I — Chapter 2",
                    ),
                ),
            isSearching = false,
            onQueryChange = {},
            onClearQuery = {},
            onResultSelected = {},
            onDismiss = {},
        )
    }
}

// Preview-only: fixed dark palette — light render is intentionally broken (see sdd/ui-previews-both-themes spec R7; color migration deferred)
@Preview(showBackground = true)
@Composable
private fun SearchBottomSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        SearchBottomSheet(
            query = "times",
            results =
                listOf(
                    SearchResult(
                        text = "It was the best of times, it was the worst of times.",
                        offset = 12,
                        chapterIndex = 0,
                        chapterTitle = "Book I — Chapter 1",
                    ),
                    SearchResult(
                        text = "In the ensuing silence, the word hung in the air.",
                        offset = 8,
                        chapterIndex = 1,
                        chapterTitle = "Book I — Chapter 2",
                    ),
                ),
            isSearching = false,
            onQueryChange = {},
            onClearQuery = {},
            onResultSelected = {},
            onDismiss = {},
        )
    }
}

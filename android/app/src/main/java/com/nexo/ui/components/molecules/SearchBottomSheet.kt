package com.nexo.ui.components.molecules

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.SearchResult
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.AccentOrange
import com.nexo.ui.theme.NexoTheme
import kotlin.math.roundToInt

/**
 * Full-text search inside the open book. Owns no search state: the caller
 * drives the query, the results and the loading flag.
 *
 * - The field takes focus and opens the keyboard on open; Search on the
 *   keyboard just hides it (results already follow the typing).
 * - Every state says something: an idle hint (accents and capitals do not
 *   matter), "type at least 2 letters", a centred spinner on the first search,
 *   a thin bar on top while refining a query that already has results, and a
 *   "no results for «x»" with a tip.
 * - Results are grouped under their chapter, with the match bold on an orange
 *   tint inside its sentence and the match's place in the book on the right.
 *
 * Kept on the reader's fixed dark palette (it opens over the dark reader
 * chrome in every reading theme); only the accent is the brand orange. It used
 * to draw its own drag handle on top of Material's, which showed two.
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
        containerColor = SheetBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = HandleColor) },
    ) {
        SearchSheetContent(
            query = query,
            results = results,
            isSearching = isSearching,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
            onResultSelected = onResultSelected,
            modifier = modifier,
        )
    }
}

@Composable
internal fun SearchSheetContent(
    query: String,
    results: List<SearchResult>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (requestFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    val trimmed = query.trim()
    val groups = remember(results) { results.groupBy { it.chapterIndex }.entries.toList() }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .imePadding(),
    ) {
        // ── Field ──────────────────────────────────────────────────
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .focusRequester(focusRequester),
            placeholder = { Text(text = stringResource(R.string.search_input_hint), color = TextSecondary) },
            leadingIcon = { Icon(imageVector = NexoIcons.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                when {
                    isSearching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentOrange)
                    query.isNotEmpty() ->
                        IconButton(onClick = onClearQuery) {
                            Icon(imageVector = NexoIcons.Close, contentDescription = stringResource(R.string.search_clear), tint = TextSecondary)
                        }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = FieldBackground,
                    unfocusedContainerColor = FieldBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AccentOrange,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // A thin bar while refining a query that already shows results.
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            androidx.compose.animation.AnimatedVisibility(visible = isSearching && results.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentOrange, trackColor = Color.Transparent)
            }
        }

        val state =
            when {
                trimmed.isEmpty() -> SearchView.IDLE
                trimmed.length < MIN_QUERY_LENGTH -> SearchView.TOO_SHORT
                isSearching && results.isEmpty() -> SearchView.LOADING
                results.isEmpty() -> SearchView.EMPTY
                else -> SearchView.RESULTS
            }
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "searchState",
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            when (view) {
                SearchView.IDLE -> SearchMessage(icon = NexoIcons.Search, title = stringResource(R.string.search_hint_idle))
                SearchView.TOO_SHORT -> SearchMessage(icon = NexoIcons.Search, title = stringResource(R.string.search_min_chars))
                SearchView.LOADING ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 48.dp), color = AccentOrange, strokeWidth = 3.dp)
                    }
                SearchView.EMPTY ->
                    SearchMessage(
                        icon = NexoIcons.Search,
                        title = stringResource(R.string.search_no_results_for, trimmed),
                        body = stringResource(R.string.search_no_results_tip),
                    )
                SearchView.RESULTS ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "summary") {
                            val summary =
                                pluralStringResource(R.plurals.search_results_found, results.size, results.size) + " " +
                                    pluralStringResource(R.plurals.search_results_in_chapters, groups.size, groups.size)
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            )
                            if (results.size >= RESULTS_CAP) {
                                Text(
                                    text = stringResource(R.string.search_results_capped, RESULTS_CAP),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                                )
                            }
                        }
                        groups.forEach { (chapterIndex, chapterResults) ->
                            item(key = "chapter-$chapterIndex") {
                                ChapterHeader(
                                    title = chapterResults.first().chapterTitle.ifBlank { stringResource(R.string.search_result_chapter, chapterIndex + 1) },
                                    count = chapterResults.size,
                                )
                            }
                            items(chapterResults, key = { "r-$chapterIndex-${it.offset}-${it.cfi}" }) { result ->
                                ResultRow(result = result, onClick = { onResultSelected(result) })
                            }
                        }
                        item(key = "bottom") { Spacer(modifier = Modifier.height(24.dp)) }
                    }
            }
        }
    }
}

private enum class SearchView { IDLE, TOO_SHORT, LOADING, EMPTY, RESULTS }

@Composable
private fun SearchMessage(
    icon: ImageVector,
    title: String,
    body: String? = null,
) {
    Column(
        // Scrolls rather than squashing when the keyboard leaves little room.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(AccentOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChapterHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.clip(CircleShape).background(FieldBackground).padding(horizontal = 9.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = resultSnippet(result),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        result.bookProgression?.let { progression ->
            Text(
                text = "${(progression * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The match bold on an orange tint inside its surrounding text. */
private fun resultSnippet(result: SearchResult): AnnotatedString =
    if (result.before.isEmpty() && result.after.isEmpty() && result.locatorJson == null) {
        // PDF hits arrive as one plain excerpt with no match split out.
        AnnotatedString(result.text)
    } else {
        buildAnnotatedString {
            append(result.before)
            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold, background = AccentOrange.copy(alpha = 0.28f))) {
                append(result.text)
            }
            append(result.after)
        }
    }

/** Mirrors the reader's search rules (MIN_SEARCH_QUERY_LENGTH / MAX_SEARCH_RESULTS). */
private const val MIN_QUERY_LENGTH = 2
private const val RESULTS_CAP = 300
private const val SHEET_HEIGHT_FRACTION = 0.88f

// Reader chrome palette (fixed dark).
private val SheetBackground = Color(0xFF161F33)
private val FieldBackground = Color(0xFF1E2840)
private val HandleColor = Color(0xFF4A5568)
private val TextPrimary = Color(0xFFDDE2F8)
private val TextSecondary = Color(0xFF8A94AD)

@Preview(showBackground = true, backgroundColor = 0xFF161F33, heightDp = 640)
@Composable
private fun SearchSheetResultsPreview() {
    NexoTheme(darkTheme = true) {
        SearchSheetContent(
            query = "madre",
            results =
                listOf(
                    SearchResult(text = "madre", offset = 0, chapterIndex = 0, chapterTitle = "Primera parte", before = "…desde el entierro de su ", after = " un fin de semana…", bookProgression = 0.02f),
                    SearchResult(text = "Madre", offset = 1, chapterIndex = 0, chapterTitle = "Primera parte", before = "Hoy ha muerto ", after = ". O tal vez ayer, no lo sé.", bookProgression = 0.03f),
                    SearchResult(text = "madre", offset = 2, chapterIndex = 3, chapterTitle = "Segunda parte", before = "…el abogado habló de mi ", after = " y del asilo…", bookProgression = 0.61f),
                ),
            isSearching = false,
            onQueryChange = {},
            onClearQuery = {},
            onResultSelected = {},
            requestFocus = false,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161F33, heightDp = 420)
@Composable
private fun SearchSheetIdlePreview() {
    NexoTheme(darkTheme = true) {
        SearchSheetContent(query = "", results = emptyList(), isSearching = false, onQueryChange = {}, onClearQuery = {}, onResultSelected = {}, requestFocus = false)
    }
}

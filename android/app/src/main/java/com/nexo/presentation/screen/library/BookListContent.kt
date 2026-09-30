package com.nexo.presentation.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.AddBookCard

/**
 * List view: ONE shared [LazyColumn] where the empty placeholder (when the
 * shelf is empty) renders first, then local books, then the add-book card,
 * and the Disponibles section (footer) last. No pinned Column.
 */
@Composable
fun BookList(
    books: List<Book>,
    readingMinutesByBook: Map<String, Long>,
    progressPercentByBook: Map<String, Float> = emptyMap(),
    onBookSelected: (String, String, String) -> Unit,
    onDelete: (Book) -> Unit,
    onImportClick: () -> Unit,
    onEdit: (Book) -> Unit,
    onMarkCompleted: (Book) -> Unit,
    onMarkPlanToRead: (Book) -> Unit,
    onShare: (Book) -> Unit,
    emptyContent: (@Composable () -> Unit)? = null,
    footerContent: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (books.isEmpty() && emptyContent != null) {
            item(key = "empty_state", contentType = { "empty" }) {
                emptyContent()
            }
        }
        items(books, key = { it.id }) { book ->
            BookListCard(
                book = book,
                minutesRead = readingMinutesByBook[book.id] ?: 0L,
                progressPercent = progressPercentByBook[book.id],
                onClick = { onBookSelected(book.id, book.filePath, book.format) },
                onDelete = { onDelete(book) },
                onEdit = { onEdit(book) },
                onMarkCompleted = { onMarkCompleted(book) },
                onMarkPlanToRead = { onMarkPlanToRead(book) },
                onShare = { onShare(book) },
            )
        }
        // The add-book card is the primary import affordance when the shelf
        // has books. When the shelf is empty, the empty-state placeholder
        // provides the import button instead, so the card is hidden.
        if (books.isNotEmpty()) {
            item(key = "add_book", contentType = { "add" }) {
                AddBookCard(onImportClick = onImportClick)
            }
        }
        if (footerContent != null) {
            item(key = "downloadable_footer", contentType = { "footer" }) {
                footerContent()
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

private val PreviewBooks =
    listOf(
        Book(
            id = "preview-book-1",
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            coverPath = null,
            filePath = "/preview/the-hobbit.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
        ),
        Book(
            id = "preview-book-2",
            title = "1984",
            author = "George Orwell",
            coverPath = null,
            filePath = "/preview/1984.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun BookListContentDarkPreview() {
    NexoTheme(darkTheme = true) {
        BookList(
            books = PreviewBooks,
            readingMinutesByBook = mapOf("preview-book-1" to 45L),
            onBookSelected = { _, _, _ -> },
            onDelete = {},
            onImportClick = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BookListContentLightPreview() {
    NexoTheme(darkTheme = false) {
        BookList(
            books = PreviewBooks,
            readingMinutesByBook = mapOf("preview-book-1" to 45L),
            onBookSelected = { _, _, _ -> },
            onDelete = {},
            onImportClick = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
        )
    }
}

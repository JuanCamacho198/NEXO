package com.nexo.presentation.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant

/**
 * Modal dialogs owned by the library screen. The edit-metadata dialog was
 * replaced by the full-screen editor (`book_edit/{bookId}` — REQ-edit-screen-1);
 * only the delete confirmation remains here.
 */
@Composable
fun LibraryDialogs(
    bookToDelete: Book?,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    bookToDelete?.let { selectedBook ->
        NexoDialog(
            title = stringResource(R.string.library_delete_title),
            body = stringResource(R.string.library_delete_message, selectedBook.title),
            confirmText = stringResource(R.string.library_delete_confirm),
            dismissText = stringResource(R.string.reader_cancel),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

private val PreviewBook =
    Book(
        id = "preview-book-1",
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        coverPath = null,
        filePath = "/preview/the-hobbit.epub",
        format = "epub",
        updatedAtEpochMillis = 0L,
    )

@Preview(showBackground = true)
@Composable
private fun LibraryDialogsDarkPreview() {
    NexoTheme(darkTheme = true) {
        LibraryDialogs(
            bookToDelete = PreviewBook,
            onDismissDelete = {},
            onConfirmDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryDialogsLightPreview() {
    NexoTheme(darkTheme = false) {
        LibraryDialogs(
            bookToDelete = PreviewBook,
            onDismissDelete = {},
            onConfirmDelete = {},
        )
    }
}

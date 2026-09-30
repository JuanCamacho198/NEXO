package com.nexo.presentation.feature.bookdetail

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingProgress
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.BookDetailViewModel
import com.nexo.presentation.viewmodel.parseChipList
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.molecules.GenreChipsDisplay
import com.nexo.ui.components.molecules.TagChipsDisplay
import com.nexo.ui.icons.NexoIcons
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    contentPadding: PaddingValues,
    viewModel: BookDetailViewModel,
    onNavigateBack: () -> Unit,
    onEditBook: () -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showActionsMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    NexoButton(
                        onClick = onNavigateBack,
                        variant = NexoButtonVariant.ICON,
                    ) {
                        Icon(
                            imageVector = NexoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.reader_cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { state.book?.let { shareBookFile(context, it) } }) {
                        Icon(
                            imageVector = NexoIcons.Share,
                            contentDescription = stringResource(R.string.book_detail_share_content_description),
                        )
                    }
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(
                                imageVector = NexoIcons.MoreVert,
                                contentDescription = stringResource(R.string.book_detail_more_content_description),
                            )
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = NexoIcons.Pencil,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = { Text(stringResource(R.string.library_menu_edit_metadata)) },
                                onClick = {
                                    showActionsMenu = false
                                    onEditBook()
                                },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(contentPadding),
        ) {
            val book = state.book
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                book == null -> {
                    Text(
                        text = stringResource(R.string.book_detail_error_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    BookDetailContent(
                        book = book,
                        progress = state.readingProgress,
                        onRatingChanged = { viewModel.updateRating(it) },
                        onContinueReading = onContinueReading,
                    )
                }
            }
        }
    }
}

/**
 * Launches the system share sheet for a book file, mirroring the
 * [androidx.core.content.FileProvider] flow used by the library context menu.
 * Missing files are ignored silently.
 */
private fun shareBookFile(
    context: Context,
    book: Book,
) {
    val mimeType =
        when (book.format.lowercase()) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "zip" -> "application/epub+zip"
            else -> "*/*"
        }
    runCatching {
        val file = File(book.filePath)
        if (!file.exists()) return@runCatching
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                context.getString(R.string.library_share_chooser_title),
            ),
        )
    }
}

@Composable
internal fun BookDetailContent(
    book: Book,
    progress: ReadingProgress?,
    onRatingChanged: (Int?) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        BookHeroSection(
            book = book,
            rating = book.userRating,
            onRatingChanged = onRatingChanged,
            onContinueReading = onContinueReading,
        )

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            BookMetadataGrid(book = book)
            SynopsisSection(synopsis = book.description)
            ReadingProgressSection(progress = progress, book = book)
            val genres = parseChipList(book.genre)
            if (genres.isNotEmpty()) {
                DetailChipsSection(title = stringResource(R.string.book_detail_genres)) {
                    GenreChipsDisplay(
                        genres = genres,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val tags = parseChipList(book.tags)
            if (tags.isNotEmpty()) {
                DetailChipsSection(title = stringResource(R.string.edit_metadata_tags)) {
                    TagChipsDisplay(
                        tags = tags,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BookDetailScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        BookDetailScreenPreviewContent()
    }
}

@Preview(showBackground = true)
@Composable
private fun BookDetailScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        BookDetailScreenPreviewContent()
    }
}

@Composable
private fun BookDetailScreenPreviewContent() {
    BookDetailContent(
        book = previewBook,
        progress = previewProgress,
        onRatingChanged = {},
        onContinueReading = { _, _, _ -> },
    )
}

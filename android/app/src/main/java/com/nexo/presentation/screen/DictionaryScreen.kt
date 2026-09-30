package com.nexo.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.domain.model.DictionaryWord
import com.nexo.presentation.viewmodel.DictionaryViewModel
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = NexoIcons.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dictionary_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.dictionary_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            NexoTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = stringResource(R.string.dictionary_search_placeholder),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.dictionary_word_count, uiState.filteredWords.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.filteredWords.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    NexoEmptyState(
                        icon = NexoIcons.LibraryBooks,
                        title = stringResource(R.string.dictionary_empty),
                        subtitle = stringResource(R.string.dictionary_empty_subtitle),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(uiState.filteredWords, key = { it.id }) { word ->
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onRequestEditWord(word) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = word.word,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (!word.definition.isNullOrBlank()) {
                                        Text(
                                            text = word.definition,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.dictionary_tap_to_add_definition),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        )
                                    }
                                    Text(
                                        text = formatDate(word.addedAtEpochMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.onRequestDeleteWord(word) },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = NexoIcons.Trash,
                                        contentDescription = stringResource(R.string.dictionary_delete_confirm),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── FAB to add word ─────────────────────────────────────
        FloatingActionButton(
            onClick = { viewModel.onShowAddDialog() },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                imageVector = NexoIcons.Add,
                contentDescription = stringResource(R.string.dictionary_add_word),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        // ── Add Word Dialog ─────────────────────────────────────
        if (uiState.showAddDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.onDismissAddDialog() },
                title = { Text(text = stringResource(R.string.dictionary_add_word)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = uiState.addWordText,
                            onValueChange = { viewModel.onAddWordTextChanged(it) },
                            placeholder = {
                                Text(stringResource(R.string.dictionary_add_word_hint))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.addDefinitionText,
                            onValueChange = { viewModel.onAddDefinitionTextChanged(it) },
                            placeholder = {
                                Text(stringResource(R.string.dictionary_add_definition_hint))
                            },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onAddWordConfirm() },
                        enabled = uiState.addWordText.isNotBlank(),
                    ) {
                        Text(text = stringResource(R.string.dictionary_add_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissAddDialog() }) {
                        Text(text = stringResource(R.string.reader_cancel))
                    }
                },
            )
        }

        // ── Delete Confirmation Dialog ──────────────────────────
        uiState.wordToDelete?.let { word ->
            AlertDialog(
                onDismissRequest = { viewModel.onDismissDeleteDialog() },
                title = { Text(text = stringResource(R.string.dictionary_delete_title)) },
                text = {
                    Text(
                        text = stringResource(R.string.dictionary_delete_message, word.word),
                    )
                },
                confirmButton = {
                    NexoButton(
                        onClick = { viewModel.onConfirmDeleteWord() },
                        variant = NexoButtonVariant.TEXT,
                    ) {
                        Text(text = stringResource(R.string.dictionary_delete_confirm))
                    }
                },
                dismissButton = {
                    NexoButton(
                        onClick = { viewModel.onDismissDeleteDialog() },
                        variant = NexoButtonVariant.TEXT,
                    ) {
                        Text(text = stringResource(R.string.reader_cancel))
                    }
                },
            )
        }

        // ── Detail / edit dialog ─────────────────────────────────
        uiState.selectedWord?.let { word ->
            AlertDialog(
                onDismissRequest = { viewModel.onDismissEditDialog() },
                title = {
                    Text(
                        text = word.word,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Column(
                        modifier =
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EntryHeader(
                            partOfSpeech = uiState.editPartOfSpeechText,
                            phonetic = uiState.editPhoneticText,
                        )

                        OutlinedTextField(
                            value = uiState.editPartOfSpeechText,
                            onValueChange = { viewModel.onEditPartOfSpeechTextChanged(it) },
                            label = { Text(stringResource(R.string.dictionary_detail_part_of_speech)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = uiState.editPhoneticText,
                            onValueChange = { viewModel.onEditPhoneticTextChanged(it) },
                            label = { Text(stringResource(R.string.dictionary_detail_phonetic)) },
                            placeholder = { Text(stringResource(R.string.dictionary_detail_phonetic_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            text = stringResource(R.string.dictionary_detail_description),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = uiState.editDefinitionText,
                            onValueChange = { viewModel.onEditDefinitionTextChanged(it) },
                            placeholder = {
                                Text(stringResource(R.string.dictionary_add_definition_hint))
                            },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            text = stringResource(R.string.dictionary_detail_example),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = uiState.editExampleText,
                            onValueChange = { viewModel.onEditExampleTextChanged(it) },
                            placeholder = {
                                Text(stringResource(R.string.dictionary_detail_example_hint))
                            },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (word.hasEvidence()) {
                            BookReferenceCard(word = word)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onEditDefinitionConfirm() },
                    ) {
                        Text(text = stringResource(R.string.dictionary_edit_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissEditDialog() }) {
                        Text(text = stringResource(R.string.reader_cancel))
                    }
                },
            )
        }
    }
}

/** Read-only preview of the entry's part of speech and phonetic, mirroring the frame's header. */
@Composable
private fun EntryHeader(
    partOfSpeech: String,
    phonetic: String,
) {
    val badge = partOfSpeech.trim()
    val pronunciation = phonetic.trim()
    if (badge.isEmpty() && pronunciation.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (badge.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (pronunciation.isNotEmpty()) {
            Text(
                text = "/$pronunciation/",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Read-only book reference and quote; it exposes no control that edits or clears the evidence. */
@Composable
private fun BookReferenceCard(word: DictionaryWord) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.dictionary_detail_book_reference),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                word.sourceBookTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                bookAttribution(word)?.let { attribution ->
                    Text(
                        text = attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                word.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                    Text(
                        text = quote,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun bookAttribution(word: DictionaryWord): String? {
    val author = word.sourceBookAuthor?.takeIf { it.isNotBlank() }
    val chapter = word.sourceChapter?.takeIf { it.isNotBlank() }
    return when {
        author != null && chapter != null ->
            "$author · ${stringResource(R.string.dictionary_detail_chapter, chapter)}"
        author != null -> author
        chapter != null -> stringResource(R.string.dictionary_detail_chapter, chapter)
        else -> null
    }
}

private fun DictionaryWord.hasEvidence(): Boolean =
    !quote.isNullOrBlank() ||
        !sourceBookTitle.isNullOrBlank() ||
        !sourceBookAuthor.isNullOrBlank() ||
        !sourceChapter.isNullOrBlank()

private fun formatDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

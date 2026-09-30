package com.nexo.presentation.feature.editmetadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.viewmodel.EditBookMetadataUiState
import com.nexo.ui.components.molecules.GenreChips
import com.nexo.ui.components.molecules.TagChips
import com.nexo.ui.util.formatSizeMb

@Composable
internal fun EditMetadataContent(
    state: EditBookMetadataUiState,
    onChangeCover: () -> Unit,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPublisherChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onPublishedDateChange: (String?) -> Unit,
    onGenreAdd: (String) -> Unit,
    onGenreRemove: (String) -> Unit,
    onTagAdd: (String) -> Unit,
    onTagRemove: (String) -> Unit,
) {
    val book = state.book ?: return

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        CoverSection(
            book = book,
            coverUri = state.coverUri,
            onChangeCover = onChangeCover,
        )

        FormField(
            label = stringResource(R.string.edit_metadata_field_title),
            value = state.title,
            onValueChange = onTitleChange,
            counter = stringResource(R.string.edit_metadata_counter, state.title.length, MAX_SHORT_FIELD),
            singleLine = true,
        )
        FormField(
            label = stringResource(R.string.edit_metadata_field_author),
            value = state.author,
            onValueChange = onAuthorChange,
            counter = stringResource(R.string.edit_metadata_counter, state.author.length, MAX_SHORT_FIELD),
            singleLine = true,
        )
        FormField(
            label = stringResource(R.string.edit_metadata_field_synopsis),
            value = state.description,
            onValueChange = onDescriptionChange,
            counter = stringResource(R.string.edit_metadata_counter, state.description.length, MAX_SYNOPSIS),
            minLines = 4,
        )

        SectionHeader(stringResource(R.string.edit_metadata_section_details))

        var showDatePicker by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.edit_metadata_field_date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showDatePicker = true }) {
                Text(
                    text = formatPublishedDate(state.publishedDate),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (showDatePicker) {
            DatePickerField(
                publishedDate = state.publishedDate,
                onPublishedDateChange = onPublishedDateChange,
                onDismiss = { showDatePicker = false },
            )
        }

        LanguageDropdown(selectedCode = state.language, onSelect = onLanguageChange)

        FormField(
            label = stringResource(R.string.edit_metadata_field_publisher),
            value = state.publisher,
            onValueChange = onPublisherChange,
            counter = stringResource(R.string.edit_metadata_counter, state.publisher.length, MAX_SHORT_FIELD),
            singleLine = true,
        )

        ReadOnlyDetailRow(
            label = stringResource(R.string.edit_metadata_field_size),
            value = remember(book.filePath) { formatSizeMb(book.filePath) },
        )
        ReadOnlyDetailRow(
            label = stringResource(R.string.edit_metadata_field_format),
            value = book.format.uppercase(),
        )

        SectionHeader(stringResource(R.string.edit_metadata_genres))
        GenreChips(
            genres = state.genres,
            max = 5,
            onAdd = onGenreAdd,
            onRemove = onGenreRemove,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(stringResource(R.string.edit_metadata_tags))
        TagChips(
            tags = state.tags,
            max = 10,
            onAdd = onTagAdd,
            onRemove = onTagRemove,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

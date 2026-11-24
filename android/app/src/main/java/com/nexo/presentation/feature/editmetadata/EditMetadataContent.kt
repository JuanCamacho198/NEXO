package com.nexo.presentation.feature.editmetadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.viewmodel.EditBookMetadataUiState
import com.nexo.ui.components.molecules.GenreChips
import com.nexo.ui.components.molecules.TagChips
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.util.formatSizeMb

/**
 * The edit form: cover first, then four titled groups — basic info (what you
 * read on the cover), details (publication facts), genres, tags — each
 * entering in sequence when the screen opens.
 */
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
    val entrance = rememberEntranceProgress()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CoverSection(
            book = book,
            coverUri = state.coverUri,
            // Unknown size (file missing) hides the pill rather than showing a dash.
            sizeLabel = remember(book.filePath) { formatSizeMb(book.filePath, fallback = "").ifEmpty { null } },
            onChangeCover = onChangeCover,
            modifier = Modifier.staggeredEnter(entrance, 0),
        )

        FormSection(
            title = stringResource(R.string.edit_metadata_section_basic),
            icon = NexoIcons.Book,
            modifier = Modifier.staggeredEnter(entrance, 1),
        ) {
            FormField(
                label = stringResource(R.string.edit_metadata_field_title),
                value = state.title,
                onValueChange = onTitleChange,
                counter = stringResource(R.string.edit_metadata_counter, state.title.length, MAX_SHORT_FIELD),
                maxLength = MAX_SHORT_FIELD,
                singleLine = true,
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                label = stringResource(R.string.edit_metadata_field_author),
                value = state.author,
                onValueChange = onAuthorChange,
                counter = stringResource(R.string.edit_metadata_counter, state.author.length, MAX_SHORT_FIELD),
                maxLength = MAX_SHORT_FIELD,
                singleLine = true,
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                label = stringResource(R.string.edit_metadata_field_synopsis),
                value = state.description,
                onValueChange = onDescriptionChange,
                counter = stringResource(R.string.edit_metadata_counter, state.description.length, MAX_SYNOPSIS),
                maxLength = MAX_SYNOPSIS,
                minLines = 4,
                imeAction = ImeAction.Default,
            )
        }

        FormSection(
            title = stringResource(R.string.edit_metadata_section_details),
            icon = NexoIcons.Info,
            modifier = Modifier.staggeredEnter(entrance, 2),
        ) {
            var showDatePicker by remember { mutableStateOf(false) }
            PickerField(
                label = stringResource(R.string.edit_metadata_field_date),
                value = formatPublishedDate(state.publishedDate),
                icon = NexoIcons.Clock,
                onClick = { showDatePicker = true },
            )
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
                maxLength = MAX_SHORT_FIELD,
                singleLine = true,
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            )
        }

        FormSection(
            title = stringResource(R.string.edit_metadata_genres),
            icon = NexoIcons.Sparkle,
            modifier = Modifier.staggeredEnter(entrance, 3),
        ) {
            GenreChips(
                genres = state.genres,
                max = 5,
                onAdd = onGenreAdd,
                onRemove = onGenreRemove,
                modifier = Modifier.fillMaxWidth(),
                addLabel = stringResource(R.string.edit_metadata_add_genre),
                removeLabel = stringResource(R.string.edit_metadata_remove_chip),
                confirmLabel = stringResource(R.string.edit_metadata_add),
                dismissLabel = stringResource(R.string.action_cancel),
            )
        }

        FormSection(
            title = stringResource(R.string.edit_metadata_tags),
            icon = NexoIcons.Tag,
            modifier = Modifier.staggeredEnter(entrance, 4),
        ) {
            TagChips(
                tags = state.tags,
                max = 10,
                onAdd = onTagAdd,
                onRemove = onTagRemove,
                modifier = Modifier.fillMaxWidth(),
                addLabel = stringResource(R.string.edit_metadata_add_tag),
                removeLabel = stringResource(R.string.edit_metadata_remove_chip),
                confirmLabel = stringResource(R.string.edit_metadata_add),
                dismissLabel = stringResource(R.string.action_cancel),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

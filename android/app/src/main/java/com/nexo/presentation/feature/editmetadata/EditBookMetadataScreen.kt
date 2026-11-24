package com.nexo.presentation.feature.editmetadata

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.domain.event.UiEvent
import com.nexo.presentation.viewmodel.EditBookMetadataUiState
import com.nexo.presentation.viewmodel.EditBookMetadataViewModel
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookMetadataScreen(
    contentPadding: PaddingValues,
    viewModel: EditBookMetadataViewModel,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collect { event ->
            if (event is UiEvent.ShowSnackbar) {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val coverPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val bytes =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }
                    if (bytes != null) viewModel.onCoverSelected(uri, bytes)
                }
            }
        }

    // Back with unsaved edits asks first; with none it simply leaves.
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestBack = {
        if (state.hasChanges && !state.isSaving) showDiscardDialog = true else onNavigateBack()
    }
    BackHandler(onBack = requestBack)

    // The host already insets for the status and navigation bars through
    // contentPadding; the inner Scaffold and its bar take no insets of their own,
    // so the top gap is applied once instead of twice.
    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.edit_metadata_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    },
                    navigationIcon = {
                        val backLabel = stringResource(R.string.nav_back)
                        Box(
                            modifier =
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(nexoColors.surface)
                                    .clickable(onClickLabel = backLabel, onClick = requestBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = NexoIcons.ArrowBack,
                                contentDescription = backLabel,
                                tint = nexoColors.textPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = nexoColors.background,
                            titleContentColor = nexoColors.textPrimary,
                        ),
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                SaveBar(
                    enabled = state.book != null && state.hasChanges && !state.isSaving,
                    isSaving = state.isSaving,
                    onSave = viewModel::save,
                )
            },
            containerColor = nexoColors.background,
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.book == null -> {
                        Text(
                            text = stringResource(R.string.book_detail_error_not_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> {
                        EditMetadataContent(
                            state = state,
                            onChangeCover = { coverPickerLauncher.launch("image/*") },
                            onTitleChange = { viewModel.onTitleChange(it.take(MAX_SHORT_FIELD)) },
                            onAuthorChange = { viewModel.onAuthorChange(it.take(MAX_SHORT_FIELD)) },
                            onDescriptionChange = { viewModel.onDescriptionChange(it.take(MAX_SYNOPSIS)) },
                            onPublisherChange = { viewModel.onPublisherChange(it.take(MAX_SHORT_FIELD)) },
                            onLanguageChange = viewModel::onLanguageChange,
                            onPublishedDateChange = viewModel::onPublishedDateChange,
                            onGenreAdd = viewModel::onGenreAdd,
                            onGenreRemove = viewModel::onGenreRemove,
                            onTagAdd = viewModel::onTagAdd,
                            onTagRemove = viewModel::onTagRemove,
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        NexoDialog(
            title = stringResource(R.string.edit_metadata_discard_title),
            body = stringResource(R.string.edit_metadata_discard_body),
            confirmText = stringResource(R.string.edit_metadata_discard_confirm),
            dismissText = stringResource(R.string.edit_metadata_keep_editing),
            onConfirm = {
                showDiscardDialog = false
                onNavigateBack()
            },
            onDismiss = { showDiscardDialog = false },
            icon = NexoIcons.Pencil,
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

/**
 * The one save action, pinned above the keyboard. Disabled until something
 * changed, so it also tells the user whether there is anything to save; while
 * saving it shows a spinner and "Saving…" instead of accepting a second tap.
 */
@Composable
private fun SaveBar(
    enabled: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    val colors = nexoColors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                // Rises with the keyboard; the nav-bar part is already in contentPadding.
                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        HorizontalDivider(color = colors.borderSubtle)
        NexoButton(
            onClick = onSave,
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            AnimatedContent(
                targetState = isSaving,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "saveBarLabel",
            ) { saving ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(if (saving) R.string.edit_metadata_saving else R.string.edit_metadata_save_changes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// Page colours of each theme: the form draws no background of its own.
@Preview(showBackground = true, backgroundColor = 0xFF0B1120)
@Composable
private fun EditBookMetadataScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        EditMetadataContent(
            state =
                EditBookMetadataUiState(
                    book = previewBook,
                    isLoading = false,
                    title = previewBook.title,
                    author = previewBook.author.orEmpty(),
                    description = previewBook.description.orEmpty(),
                    genres = listOf("Fantasy", "Classics"),
                    tags = listOf("favorites", "adventure"),
                    language = previewBook.language,
                    publisher = previewBook.publisher.orEmpty(),
                    publishedDate = previewBook.publishedDate,
                ),
            onChangeCover = {},
            onTitleChange = {},
            onAuthorChange = {},
            onDescriptionChange = {},
            onPublisherChange = {},
            onLanguageChange = {},
            onPublishedDateChange = {},
            onGenreAdd = {},
            onGenreRemove = {},
            onTagAdd = {},
            onTagRemove = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F3EE)
@Composable
private fun EditBookMetadataScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        EditMetadataContent(
            state =
                EditBookMetadataUiState(
                    book = previewBook,
                    isLoading = false,
                    title = previewBook.title,
                    author = previewBook.author.orEmpty(),
                    description = previewBook.description.orEmpty(),
                    genres = listOf("Fantasy"),
                    tags = listOf("favorites"),
                    language = previewBook.language,
                    publisher = previewBook.publisher.orEmpty(),
                    publishedDate = previewBook.publishedDate,
                ),
            onChangeCover = {},
            onTitleChange = {},
            onAuthorChange = {},
            onDescriptionChange = {},
            onPublisherChange = {},
            onLanguageChange = {},
            onPublishedDateChange = {},
            onGenreAdd = {},
            onGenreRemove = {},
            onTagAdd = {},
            onTagRemove = {},
        )
    }
}

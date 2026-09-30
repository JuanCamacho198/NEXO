package com.nexo.presentation.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import com.nexo.presentation.UiEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Collects 6 [SharedFlow<UiEvent>] streams and dispatches each event verbatim
 * as the monolithic host previously did inline.
 *
 * Preserves exhaustive handling:
 * - ShowSnackbar -> snackbarHostState.showSnackbar
 * - ShowToast -> Toast.makeText
 * - ShareText -> ACTION_SEND chooser
 * - ShareFile -> FileProvider ACTION_SEND chooser
 * - CopyToClipboard -> clipboard + snackbar
 * - OpenBookAtLocation -> sets selectedBook* + navigateToCfiAfterLoad + nav to reader
 */
@Composable
fun GlobalEventCollector(
    libraryUiEvent: SharedFlow<UiEvent>,
    readerUiEvent: SharedFlow<UiEvent>,
    highlightsUiEvent: SharedFlow<UiEvent>,
    statisticsUiEvent: SharedFlow<UiEvent>,
    homeUiEvent: SharedFlow<UiEvent>,
    authUiEvent: SharedFlow<UiEvent>,
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    context: Context,
    onOpenBookAtLocation: suspend (UiEvent.OpenBookAtLocation) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flows =
        listOf(
            libraryUiEvent,
            readerUiEvent,
            highlightsUiEvent,
            statisticsUiEvent,
            homeUiEvent,
            authUiEvent,
        )
    flows.forEach { flow ->
        LaunchedEffect(flow) {
            flow.collect { event ->
                when (event) {
                    is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                    is UiEvent.ShowToast ->
                        android.widget.Toast
                            .makeText(
                                context,
                                event.message,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    is UiEvent.ShareText -> {
                        val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, event.text)
                            }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(com.nexo.R.string.context_menu_share),
                            ),
                        )
                    }
                    is UiEvent.ShareFile -> {
                        val file = java.io.File(event.filePath)
                        val uri =
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                        val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = event.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(com.nexo.R.string.library_share_chooser_title),
                            ),
                        )
                    }
                    is UiEvent.CopyToClipboard -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("highlight", event.text))
                        snackbarHostState.showSnackbar(context.getString(com.nexo.R.string.highlights_snackbar_copied))
                    }
                    is UiEvent.OpenBookAtLocation -> {
                        scope.launch {
                            onOpenBookAtLocation(event)
                        }
                    }
                }
            }
        }
    }
}

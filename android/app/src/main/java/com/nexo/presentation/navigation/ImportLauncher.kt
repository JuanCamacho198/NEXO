package com.nexo.presentation.navigation

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.data.storage.AccountStorageLayout
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.util.getContentDisplayName
import com.nexo.presentation.viewmodel.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hoists the OpenDocument import launcher out of the Home composable.
 *
 * Preserves verbatim IO copy logic (mkdirs, getContentDisplayName, PDF/EPUB dir
 * selection, context.contentResolver.getType + openInputStream, Dispatchers.IO
 * copy, libraryViewModel.importPdfBook / importBookFromEpub) previously inline
 * in NexoNavHost's Home composable.
 *
 * Files land in the active account's own subtree (`pdfs/{userId}/`,
 * `epubs/{userId}/`) via [AccountStorageLayout], so two accounts importing the
 * same filename never overwrite each other. When no account is active (signed
 * out, or the anonymous/local session of decision D5) the import is refused
 * before any byte is written to disk.
 *
 * Usage:
 * ```
 * val importLauncher = rememberImportLauncher(libraryViewModel, activeUser)
 * homeGraph(..., onImportBook = { importLauncher.launch(arrayOf(...)) })
 * ```
 */
@Composable
fun rememberImportLauncher(
    libraryViewModel: LibraryViewModel,
    activeUser: ActiveUserProvider,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layout = AccountStorageLayout(context.filesDir)
    val refusedMessage = stringResource(R.string.import_requires_account)

    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult

            // D5: the anonymous/local path persists nothing. Refuse before the
            // first filesystem write rather than discovering it mid-import.
            val userId = activeUser.currentUserId()
            if (userId == null) {
                android.widget.Toast
                    .makeText(context, refusedMessage, android.widget.Toast.LENGTH_SHORT)
                    .show()
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                runCatching {
                    val fileName =
                        getContentDisplayName(context, uri)
                            ?: uri.lastPathSegment
                            ?: "imported_book"
                    val mimeType = context.contentResolver.getType(uri)

                    if (fileName.endsWith(".pdf", true) || mimeType == "application/pdf") {
                        val pdfFile = layout.namedFile(AccountStorageLayout.PDFS_DIR, userId, fileName)
                        pdfFile.parentFile?.mkdirs()
                        withContext(Dispatchers.IO) {
                            copyInto(context, uri, pdfFile)
                        }
                        libraryViewModel.importPdfBook(
                            sourcePath = pdfFile.absolutePath,
                            fallbackTitle = fileName.removeSuffix(".pdf"),
                            pdfFile = pdfFile,
                        )
                    } else {
                        val epubFile = layout.namedFile(AccountStorageLayout.EPUBS_DIR, userId, fileName)
                        epubFile.parentFile?.mkdirs()
                        withContext(Dispatchers.IO) {
                            copyInto(context, uri, epubFile)
                        }
                        libraryViewModel.importBookFromEpub(
                            sourcePath = epubFile.absolutePath,
                            fallbackTitle = fileName.removeSuffix(".epub"),
                            inputStreamProvider = { epubFile.inputStream() },
                        )
                    }
                }.onFailure { error ->
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.import_failed, error.message.orEmpty()),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        },
    )
}

private fun copyInto(
    context: android.content.Context,
    uri: Uri,
    target: File,
) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

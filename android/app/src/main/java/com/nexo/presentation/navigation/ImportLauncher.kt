package com.nexo.presentation.navigation

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
 * Usage:
 * ```
 * val importLauncher = rememberImportLauncher(libraryViewModel)
 * homeGraph(..., onImportBook = { importLauncher.launch(arrayOf(...)) })
 * ```
 */
@Composable
fun rememberImportLauncher(libraryViewModel: LibraryViewModel): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch {
                runCatching {
                    val fileName =
                        getContentDisplayName(context, uri)
                            ?: uri.lastPathSegment
                            ?: "imported_book"
                    val mimeType = context.contentResolver.getType(uri)

                    if (fileName.endsWith(".pdf", true) || mimeType == "application/pdf") {
                        val pdfDir = File(context.filesDir, "pdfs")
                        if (!pdfDir.exists()) pdfDir.mkdirs()
                        val pdfFile = File(pdfDir, fileName)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                pdfFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        libraryViewModel.importPdfBook(
                            sourcePath = pdfFile.absolutePath,
                            fallbackTitle = fileName.removeSuffix(".pdf"),
                            pdfFile = pdfFile,
                        )
                    } else {
                        val epubDir = File(context.filesDir, "epubs")
                        if (!epubDir.exists()) epubDir.mkdirs()
                        val epubFile = File(epubDir, fileName)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                epubFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
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
                            "Import failed: ${error.message}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        },
    )
}

package com.nexo.presentation.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.presentation.viewmodel.LibraryViewModel
import com.nexo.presentation.viewmodel.library.BookImportState
import com.nexo.ui.components.atoms.NexoImportOverlay
import kotlinx.coroutines.delay

private const val IMPORT_OVERLAY_WATCHDOG_TIMEOUT_MS = 120_000L

/**
 * Collects [LibraryViewModel.importState] → [NexoImportOverlay] + 120s watchdog.
 *
 * The watchdog resets a stuck import after 120s so the overlay can never trap
 * the screen.
 */
@Composable
fun ImportOverlayHost(
    libraryViewModel: LibraryViewModel,
) {
    val importState by libraryViewModel.importState.collectAsStateWithLifecycle()

    LaunchedEffect(importState) {
        if (importState !is BookImportState.Idle) {
            delay(IMPORT_OVERLAY_WATCHDOG_TIMEOUT_MS)
            libraryViewModel.resetImportState()
        }
    }

    // Always composed: the overlay animates itself in and out from importState.
    // Composing it only while busy skipped both animations (it was already
    // visible on its first frame and gone before it could fade out).
    NexoImportOverlay(
        importState = importState,
        modifier = Modifier.fillMaxSize(),
    )
}

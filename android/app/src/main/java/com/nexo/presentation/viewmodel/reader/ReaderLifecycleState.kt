package com.nexo.presentation.viewmodel.reader

import com.nexo.domain.model.ReadingProgress
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Session slice alias (SDD reader-facade-split, slice 4): VM re-export type. */
typealias SessionUiState = ReaderLifecycleState

data class ReaderLifecycleState(
    val selectedBookId: String? = null,
    val bookFilePath: String? = null,
    val bookFormat: String? = null,
    val chapters: List<BookChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentPdfPage: Int = 0,
    val totalPdfPages: Int = 0,
    val readingProgress: ReadingProgress? = null,
    val readiumPublication: Publication? = null,
    val readiumLocator: Locator? = null,
    val readiumViewportHeight: Int = 0,
    val readiumViewportWidth: Int = 0,
    val readiumSelectionLocator: Locator? = null,
    val progressPercent: Float = 0f,
    val progressLabel: String = "",
    val showTocSheet: Boolean = false,
    val previewText: String = "",
    val isLoading: Boolean = true,
    val loadTimeMs: Long? = null,
    val error: String? = null,
    /**
     * Whether the reader is allowed to PERSIST a progress emission.
     *
     * Set by the loader: false while a book is being opened, true only once the
     * saved position (the persisted locator, or the cloud copy that replaces it)
     * has been handed to the navigator. Until then the navigator reports the
     * position it happens to be resting on — the reading order's first resource,
     * i.e. the cover — and persisting that would overwrite the reader's real
     * position with 0%.
     */
    val progressPersistenceArmed: Boolean = false,
)

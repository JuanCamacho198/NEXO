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
    /** Title of the chapter being read, cleaned for one line ([cleanChapterTitle]); empty when the book gives none. */
    val chapterTitle: String = "",
    /**
     * Pages left in the current chapter at the current typography, from the
     * chapter's visible text ([chapterPagesRemaining]). `null` while that text is
     * still being measured: the footer shows nothing rather than a guess.
     */
    val chapterPagesLeft: Int? = null,
    /**
     * Each chapter's start as a fraction of the book, aligned with [chapters];
     * `null` where unknown. Empty until Readium's positions are computed. Drives
     * the chapter ticks on the progress bar and the chapter named while scrubbing.
     */
    val chapterStartFractions: List<Float?> = emptyList(),
    /** Where the reader was before the last scrub jump; non-null while "go back" is offered. */
    val scrubReturnLocator: Locator? = null,
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

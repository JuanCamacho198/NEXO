package com.nexo.presentation.viewmodel.reader

import androidx.compose.runtime.Immutable

/**
 * A chapter (or nested sub-chapter) entry in the reader's table of contents.
 *
 * @param index Position of this entry in the publication's reading order —
 *   used for navigation (jump to this chapter) and for matching the current
 *   chapter. Multiple TOC entries (parts + their sub-chapters) can share the
 *   same reading-order index when they live in the same spine resource.
 * @param id Unique key for this entry (typically the href).
 * @param title Display title from the EPUB nav (TOC). Never a generic
 *   "Chapter N" fallback when the TOC supplies a real title.
 * @param href The resource href this entry points to.
 * @param depth Hierarchy level: 0 = top-level chapter, 1 = sub-chapter,
 *   2 = sub-sub-chapter, etc. Drives the indentation in the TOC sheet.
 */
@Immutable
data class BookChapter(
    val index: Int,
    val id: String,
    val title: String,
    val href: String,
    val depth: Int = 0,
)

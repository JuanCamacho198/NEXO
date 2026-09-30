package com.nexo.domain.model

import androidx.compose.runtime.Immutable

/**
 * A single search result found within a book's content.
 *
 * @property text Snippet of surrounding text with the match highlighted
 * @property offset Character offset of the match within the containing element
 * @property page Approximate scroll position / page y-offset for navigation
 * @property chapterIndex Index of the chapter (EPUB) or zero (PDF)
 * @property rect Optional bounding rectangle in WebView coordinates (EPUB only)
 */
@Immutable
data class SearchResult(
    val text: String,
    val offset: Int,
    val page: Float = 0f,
    val chapterIndex: Int = 0,
    val rect: BoundingRect? = null,
    val chapterTitle: String = "",
    val cfi: String = "",
)

/**
 * Pure-domain bounding rectangle in WebView coordinates. Replaces
 * `android.graphics.Rect` so the project's own `domain` layer carries no
 * Android runtime dependency (SDD android-stack-modernization S3, R7).
 */
@Immutable
data class BoundingRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

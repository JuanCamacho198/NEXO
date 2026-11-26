package com.nexo.domain.model

import androidx.compose.runtime.Immutable

/**
 * One box of a PDF highlight, page-relative, in CSS px at scale 1 with a top-left origin.
 *
 * That is exactly the space PDF.js reports a selection in, so a highlight stores the numbers
 * the viewer produced and every consumer scales them at paint time (Android multiplies by
 * `scale * density`, desktop by `scale`). The type carries no page, no scale and no density:
 * those belong to whoever paints it.
 *
 * Multiple boxes exist because a selection can span several lines. Rects from more than one
 * page are out of scope — a highlight keeps the boxes of the page it starts on.
 */
@Immutable
data class HighlightRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

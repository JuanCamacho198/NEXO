package com.nexo.ui.layout

/**
 * What a multi-column surface should do at a given width.
 *
 * Kept as a plain enum, not a Compose type, so the decision is unit-testable
 * without an Activity, a WindowManager or a `@Preview`, and so every surface
 * shares one rule instead of inventing its own breakpoints.
 */
enum class AdaptiveColumns {
    /** The phone layout. Exactly what shipped before large-screen support. */
    Compact,

    /** Room for one more column beside the phone layout. */
    Medium,

    /** Tablet/foldable landscape: use the width instead of stretching cells. */
    Expanded,
}

/**
 * Breakpoint decisions for the reader, library and discover surfaces.
 *
 * Breakpoints are the Material 3 window-size classes (600dp / 840dp), applied to
 * width only: the surfaces here scroll vertically, so height class never changes
 * the column count.
 */
object AdaptiveLayout {
    const val MEDIUM_MIN_WIDTH_DP = 600
    const val EXPANDED_MIN_WIDTH_DP = 840

    /**
     * Reading measures, in dp. A single text column past ~900dp stops being
     * readable (the eye loses the line return), which is the whole reason a
     * tablet needs a cap rather than a wider page.
     */
    const val READER_MAX_COLUMN_WIDTH_DP = 720

    /**
     * Upper bound on the reader column width for a viewport.
     *
     * At compact width this returns the available width unchanged, so the phone
     * layout is byte-identical: the cap only bites from [MEDIUM_MIN_WIDTH_DP] up.
     */
    fun readerMaxWidthDp(availableWidthDp: Int): Int =
        if (availableWidthDp < MEDIUM_MIN_WIDTH_DP) availableWidthDp else READER_MAX_COLUMN_WIDTH_DP

    /** Fixed column count for the book grids (library shelf, discover results). */
    fun gridColumnCount(widthDp: Int): Int =
        when (columnsForWidth(widthDp)) {
            AdaptiveColumns.Compact -> 2
            AdaptiveColumns.Medium -> 3
            AdaptiveColumns.Expanded -> 4
        }

    fun columnsForWidth(widthDp: Int): AdaptiveColumns =
        when {
            widthDp >= EXPANDED_MIN_WIDTH_DP -> AdaptiveColumns.Expanded
            widthDp >= MEDIUM_MIN_WIDTH_DP -> AdaptiveColumns.Medium
            else -> AdaptiveColumns.Compact
        }

    /**
     * Whether a surface should show its list and its detail side by side.
     *
     * Only at [AdaptiveColumns.Expanded]: at 600dp a second pane leaves each side
     * narrower than the phone it replaced, which is worse, not better.
     */
    fun showsListAndDetail(widthDp: Int): Boolean = columnsForWidth(widthDp) == AdaptiveColumns.Expanded
}

package com.nexo.debug

/**
 * Where the app was when it crashed.
 *
 * The feedback sheet used to always render a *book* context card, so a crash
 * on Home / Settings / Library showed invented book data ("La Odisea",
 * "Capítulo IX"). [CrashContext] models both cases so the sheet can tell the
 * truth: a book card when we know the book, a plain screen row otherwise.
 *
 * ## Privacy policy (mirrors [FeedbackCapture]'s allowlist — spec D2)
 *
 * - [InBook.bookId], [InBook.chapterIndex] and [InBook.page] are ids/counters:
 *   ALWAYS attached to Sentry.
 * - [InBook.title] and [InBook.chapterLabel] are user library content: attached
 *   ONLY on feedback events (the feedback path is their only writer).
 * - [InScreen.screenLabel] describes an app screen, not the user's content.
 *   It is treated like the ids: it is attached whenever we know it, because it
 *   says "the crash happened in Settings", never *what* the user was reading or
 *   typing. It is still never stored as note text.
 *
 * ## Persistence invariant
 *
 * [FeedbackPersistence] round-trips [FeedbackEvent.FeedbackEntry] JSON with the
 * flat `bookId`/`chapterIndex`/`page`/`title`/`chapterLabel` keys. This type is
 * a presentation-layer decision only and must NOT change that wire shape —
 * entries saved by older builds keep loading. See [toEntryFields].
 */
sealed interface CrashContext {
    /** The crash happened while the user was reading [title]. */
    data class InBook(
        val bookId: String,
        val title: String?,
        val chapterLabel: String?,
        val chapterIndex: Int?,
        val page: Int?,
        /** Local cover file path, when the book has cover art bound. */
        val coverPath: String? = null,
    ) : CrashContext

    /** The crash happened somewhere that is not a book (Home, Settings, …). */
    data class InScreen(
        /**
         * Untranslated identifier of the screen — a [com.nexo.presentation.navigation.NexoDestination]
         * route, e.g. `home` or `settings`. The UI resolves it to a localized
         * label; an unknown value falls back to a generic label.
         */
        val screenRoute: String?,
    ) : CrashContext

    companion object {
        /**
         * Build the best context we can from whatever the crash path recorded.
         *
         * A book context is only claimed when we actually have a [bookId]; the
         * next-launch prompt passes everything empty (the process died before
         * any reading session started), and rendering a book card from that
         * would be a lie.
         */
        fun of(
            bookId: String?,
            title: String?,
            chapterLabel: String?,
            chapterIndex: Int?,
            page: Int?,
            coverPath: String? = null,
            screenRoute: String? = null,
        ): CrashContext =
            if (!bookId.isNullOrBlank()) {
                InBook(
                    bookId = bookId,
                    title = title,
                    chapterLabel = chapterLabel,
                    chapterIndex = chapterIndex,
                    page = page,
                    coverPath = coverPath,
                )
            } else {
                InScreen(screenRoute = screenRoute)
            }
    }
}

/**
 * Presentation-ready view of a [CrashContext]. Pure and unit-testable: the
 * composable resolves resource ids, this decides *which* ones to show.
 *
 * - [bookTitle] / [chapterLine] are non-null only for books, and are already
 *   free of the old fake `page N de 318` total — a real total page count is not
 *   known on this path, so it is simply not claimed.
 * - [hasCover] is false without a real cover path: no more identical brown
 *   placeholder rectangle for every book.
 */
data class CrashContextViewModel(
    val isBook: Boolean,
    val bookTitle: String?,
    val chapterLine: ChapterLine?,
    val coverPath: String?,
    val hasCover: Boolean,
    val screenRoute: String?,
) {
    /**
     * The chapter/page line, decomposed so the composable can format it from
     * resources instead of building an English/Spanish string in code.
     */
    data class ChapterLine(
        val chapterLabel: String?,
        val page: Int?,
    )

    companion object {
        fun from(context: CrashContext): CrashContextViewModel =
            when (context) {
                is CrashContext.InBook -> {
                    val label = context.chapterLabel?.takeIf { it.isNotBlank() }
                    val page = context.page?.takeIf { it > 0 }
                    val line = if (label == null && page == null) null else ChapterLine(label, page)
                    val cover = context.coverPath?.takeIf { it.isNotBlank() }
                    CrashContextViewModel(
                        isBook = true,
                        bookTitle = context.title?.takeIf { it.isNotBlank() },
                        chapterLine = line,
                        coverPath = cover,
                        hasCover = cover != null,
                        screenRoute = null,
                    )
                }

                is CrashContext.InScreen ->
                    CrashContextViewModel(
                        isBook = false,
                        bookTitle = null,
                        chapterLine = null,
                        coverPath = null,
                        hasCover = false,
                        screenRoute = context.screenRoute?.takeIf { it.isNotBlank() },
                    )
            }
    }
}

/** Flat book fields as they appear in a persisted [FeedbackEvent.FeedbackEntry]. */
internal data class CrashContextEntryFields(
    val bookId: String?,
    val chapterIndex: Int?,
    val page: Int?,
    val title: String?,
    val chapterLabel: String?,
)

/**
 * Map a context onto the persisted entry fields. A non-book crash persists no
 * book fields at all (nulls), which keeps the existing JSON shape intact.
 */
internal fun CrashContext.toEntryFields(): CrashContextEntryFields =
    when (this) {
        is CrashContext.InBook ->
            CrashContextEntryFields(
                bookId = bookId.takeIf { it.isNotBlank() },
                chapterIndex = chapterIndex,
                page = page,
                title = title,
                chapterLabel = chapterLabel,
            )

        is CrashContext.InScreen -> CrashContextEntryFields(null, null, null, null, null)
    }

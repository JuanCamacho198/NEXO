package com.nexo.presentation.viewmodel.reader.interaction

import android.util.Log
import com.nexo.domain.model.Bookmark
import com.nexo.domain.repository.ReaderRepository
import com.nexo.presentation.viewmodel.CfiMigrator
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/**
 * Bookmark observation and creation (format-sensitive cfi vs pdfpage:).
 */
internal class BookmarkManager(
    private val store: InteractionStateStore,
    private val readerRepository: ReaderRepository,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    companion object {
        private const val TAG = "BookmarkManager"
    }

    private var observeBookmarksJob: Job? = null

    fun observeBook(bookId: String) {
        observeBookmarksJob?.cancel()
        observeBookmarksJob =
            scope.launch(mainDispatcher) {
                readerRepository.observeBookmarks(bookId).collect { bookmarks ->
                    store.update { it.copy(bookmarks = bookmarks) }
                }
            }
    }

    fun testStopObserving() {
        observeBookmarksJob?.cancel()
    }

    fun createBookmark(
        bookId: String,
        cfiLocation: String,
        titleOrSnippet: String,
        locatorJson: String? = null,
    ) {
        scope.launch(mainDispatcher) {
            val bookmark =
                Bookmark(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    cfiLocation = cfiLocation,
                    titleOrSnippet = titleOrSnippet,
                    locatorJson = locatorJson,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    deletedAtEpochMillis = null,
                )
            readerRepository.upsertBookmark(bookmark)
            Log.d(TAG, "Bookmark created: ${bookmark.id}")
        }
    }

    fun createBookmarkFromCurrentPosition(
        selectedBookId: String?,
        bookFormat: String?,
        currentPdfPage: Int,
        chapters: List<BookChapter>,
        currentChapterIndex: Int,
        readiumLocator: Locator? = null,
    ) {
        val bookId = selectedBookId ?: return
        val format = bookFormat
        when (format) {
            "pdf" -> {
                val cfiLocation = "pdfpage:$currentPdfPage"
                val titleOrSnippet = "Page ${currentPdfPage + 1}"
                createBookmark(bookId, cfiLocation, titleOrSnippet)
            }
            else -> {
                val chapter = chapters.getOrNull(currentChapterIndex) ?: return
                if (readiumLocator != null) {
                    val locatorJson = CfiMigrator.locatorToJson(readiumLocator)
                    val preciseCfi = "readium:${readiumLocator.href}"
                    val titleOrSnippet = chapter.title.ifBlank { "Chapter ${currentChapterIndex + 1}" }
                    createBookmark(bookId = bookId, cfiLocation = preciseCfi, titleOrSnippet = titleOrSnippet, locatorJson = locatorJson)
                } else {
                    val cfiLocation = "epubcfi(/6/${currentChapterIndex + 1})"
                    val titleOrSnippet = "Chapter ${currentChapterIndex + 1}: ${chapter.title}"
                    createBookmark(bookId, cfiLocation, titleOrSnippet)
                }
            }
        }
    }

    override fun onCleared() {
        observeBookmarksJob?.cancel()
    }
}

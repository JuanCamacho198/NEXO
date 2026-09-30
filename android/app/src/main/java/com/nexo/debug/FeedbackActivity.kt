package com.nexo.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.nexo.NexoApplication
import com.nexo.presentation.theme.NexoTheme

/**
 * Host activity for the crash-feedback bottom sheet (PR4 / tasks 4.3-4.4).
 *
 * Triggers:
 * - Next-launch prompt: [com.nexo.MainActivity] launches this activity
 *   when [FeedbackPersistence.readLastEventId] is non-null AND not in
 *   [FeedbackPersistence.readDismissed].
 * - `KEY_LAST_CRASH` path: [com.nexo.debug.CrashDetailActivity] finishes
 *   itself and returns control to [com.nexo.MainActivity]; the next-launch
 *   prompt fires from MainActivity, so no explicit launch is needed.
 *
 * Lifecycle:
 * - On `onCreate`: reads the persisted queue/dismissed/lastEventId, builds
 *   the [FeedbackViewModel] via [FeedbackViewModel.factory], and renders
 *   [FeedbackSheet].
 * - On sheet dismiss: persists the current queue + dismissed set back to
 *   SharedPreferences (so dismiss-once survives the next launch).
 * - On sheet submit: Sentry `captureFeedback` runs inline (best-effort);
 *   the queue is updated synchronously so the next-launch prompt is
 *   consistent.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class FeedbackActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EVENT_ID = "feedback_event_id"
        const val EXTRA_BOOK_ID = "feedback_book_id"
        const val EXTRA_BOOK_TITLE = "feedback_book_title"
        const val EXTRA_CHAPTER_LABEL = "feedback_chapter_label"
        const val EXTRA_CHAPTER_INDEX = "feedback_chapter_index"
        const val EXTRA_PAGE = "feedback_page"

        fun intent(
            context: Context,
            eventId: String?,
            book: FeedbackEvent.BookMeta,
        ): Intent =
            Intent(context, FeedbackActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_EVENT_ID, eventId)
                putExtra(EXTRA_BOOK_ID, book.bookId)
                putExtra(EXTRA_BOOK_TITLE, book.title)
                putExtra(EXTRA_CHAPTER_LABEL, book.chapterLabel)
                if (book.chapterIndex != null) putExtra(EXTRA_CHAPTER_INDEX, book.chapterIndex)
                if (book.page != null) putExtra(EXTRA_PAGE, book.page)
            }
    }

    private lateinit var persistence: FeedbackPersistence

    private val viewModel: FeedbackViewModel by viewModels {
        persistence = FeedbackPersistence(applicationContext)
        val queue = persistence.readQueue()
        val dismissed = persistence.readDismissed()
        val book =
            FeedbackEvent.BookMeta(
                bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: "",
                title = intent.getStringExtra(EXTRA_BOOK_TITLE),
                chapterLabel = intent.getStringExtra(EXTRA_CHAPTER_LABEL),
                chapterIndex =
                    intent
                        .getIntExtra(EXTRA_CHAPTER_INDEX, -1)
                        .takeIf { it >= 0 },
                page = intent.getIntExtra(EXTRA_PAGE, -1).takeIf { it >= 0 },
            )
        val eventId =
            intent.getStringExtra(EXTRA_EVENT_ID)
                ?: (applicationContext as? NexoApplication)?.feedbackPersistence?.readLastEventId()
        FeedbackViewModel.factory(
            initialQueue = queue,
            initialDismissed = dismissed,
            book = book,
            eventId = eventId,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexoTheme(darkTheme = true) {
                FeedbackSheet(
                    viewModel = viewModel,
                    onDismiss = { persistAndFinish() },
                )
            }
        }
    }

    private fun persistAndFinish() {
        // Snapshot the current queue + dismissed set; the user may have
        // enqueued offline feedback or dismissed the prompt. Always write
        // back, even on cancel — the dismiss-once invariant requires it.
        val (queue, dismissed) = viewModel.exportForPersistence()
        persistence.writeQueue(queue)
        persistence.writeDismissed(dismissed)
        // Clear the last-event-id so the next-launch prompt doesn't re-fire
        // after the user has either sent or dismissed feedback.
        persistence.clearLastEventId()
        finish()
    }
}

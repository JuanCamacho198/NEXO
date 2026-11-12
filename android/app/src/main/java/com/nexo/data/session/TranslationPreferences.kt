package com.nexo.data.session

import android.content.Context
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.translation.TranslationSettings

/**
 * Persists the reader's translation languages (source/target) to an
 * account-scoped SharedPreferences file (T2 of `translation-preferences`).
 *
 * Mirrors [ReaderPreferences] deliberately: the file is
 * `nexo_translation_prefs__{userId}`, resolved at call time from
 * [ActiveUserProvider] (see [AccountPreferences] for why the file name — not the
 * keys — carries isolation). Before this feature the target language was the
 * system locale and nothing was persisted, so the English-book-on-an-English-
 * phone case had no way out.
 *
 * With no active account (signed out, or the anonymous session of decision D5)
 * [load] returns defaults and [save] is a no-op: the anonymous path persists
 * nothing. The language falls back to the system locale, which is exactly the
 * behaviour that shipped before this class existed.
 *
 * ## Per-book SOURCE choices (Group 2)
 *
 * The reader's source choice for a single book lives in THIS file, under one key
 * per book id (`book_source__{bookId}`), rather than in a Room table.
 *
 * Why here and not Room:
 *
 * - **It is a preference, not relational data.** It has no columns, no queries,
 *   no joins and no history — reading it is "what did the reader pick for this
 *   book", which is what a key answers in one lookup. A Room table would need a
 *   new entity, DAO, schema bump and migration for a single nullable string.
 * - **It shares the account lifetime exactly.** A book's choice is scoped to the
 *   account that made it, which is the same file-level isolation
 *   [AccountPreferences] already applies. A Room table would need its own
 *   `user_id` column and its own isolation proof — reimplementing, less safely,
 *   what the file name already guarantees.
 * - **Sign-out hygiene stays a file operation.** Deleting an account deletes one
 *   file; a Room table would leave per-book rows to sweep.
 * - **The read is on the same path as the account choice.** `onTranslateSelection`
 *   already calls [load]; the per-book value is available in that same call with
 *   no second data source to consult, so the resolver gains an input and not a
 *   dependency.
 *
 * The one thing this store does NOT offer is a query ("which books have a
 * choice"), and nothing needs one: the value is always read for the book the
 * reader is currently in.
 *
 * This is NOT the UI language. [AppLanguagePreferences] is global on purpose and
 * lives in its own file; the two must not be conflated.
 */
class TranslationPreferences(
    context: Context,
    private val activeUser: ActiveUserProvider,
) {
    private val appContext = context.applicationContext

    /** The active account's file, or `null` when no account owns this state. */
    private fun prefs() = AccountPreferences.open(appContext, PREFS_NAME, activeUser)

    /**
     * Loads the saved languages, or the "follow the system" defaults when
     * nothing is stored or no account is active.
     */
    fun load(): TranslationSettings {
        val prefs = prefs() ?: return TranslationSettings()
        return TranslationSettings(
            targetLanguage = prefs.getString(KEY_TARGET, null),
            sourceLanguage = prefs.getString(KEY_SOURCE, null),
        )
    }

    /**
     * Persists the languages for the active account. A no-op when no account is
     * active (decision D5). A `null` language removes its key so the store
     * returns to "follow the system" instead of pinning a stale code.
     */
    fun save(settings: TranslationSettings) {
        val prefs = prefs() ?: return
        val editor = prefs.edit()
        editor.putOrRemove(KEY_TARGET, settings.targetLanguage)
        editor.putOrRemove(KEY_SOURCE, settings.sourceLanguage)
        editor.apply()
    }

    /**
     * The reader's source choice for [bookId], or `null` when they made none.
     *
     * `null` is "no choice for this book", NOT "automatic": the cascade falls
     * through to the account preference and the rest of the source ladder. That
     * distinction is what lets "Automatic (detect)" in the language bar be a
     * genuine CLEAR — [clearBookSource] removes the key so the next resolution
     * behaves exactly as if the reader had never chosen.
     */
    fun bookSource(bookId: String): String? {
        if (bookId.isBlank()) return null
        return prefs()?.getString(bookSourceKey(bookId), null)
    }

    /**
     * Persists the reader's source choice for [bookId]. A blank [language] — or
     * a blank [bookId] — CLEARS the choice rather than storing an empty code, so
     * a clear and a never-chosen book are indistinguishable downstream. That is
     * deliberate: both mean "no choice here".
     *
     * A no-op when no account is active, matching [save].
     */
    fun saveBookSource(bookId: String, language: String?) {
        if (bookId.isBlank()) return
        val prefs = prefs() ?: return
        val editor = prefs.edit()
        editor.putOrRemove(bookSourceKey(bookId), language)
        editor.apply()
    }

    /**
     * Removes the reader's source choice for [bookId], returning the book to the
     * account preference and the rest of the cascade. This is what the bar's
     * "Automatic (detect)" entry does — a different promise from Settings'
     * "Detect automatically", because it discards a choice made HERE.
     */
    fun clearBookSource(bookId: String) {
        saveBookSource(bookId, null)
    }

    private fun bookSourceKey(bookId: String) = "$KEY_BOOK_SOURCE_PREFIX$bookId"

    private fun android.content.SharedPreferences.Editor.putOrRemove(
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    companion object {
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_translation_prefs"
        private const val KEY_TARGET = "target_language"
        private const val KEY_SOURCE = "source_language"

        /**
         * One key per book: `book_source__{bookId}`. The book id is a UUID and
         * cannot contain the separator, so the key space cannot collide with the
         * fixed keys above.
         */
        private const val KEY_BOOK_SOURCE_PREFIX = "book_source__"
    }
}

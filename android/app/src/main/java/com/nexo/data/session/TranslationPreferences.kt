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
    }
}

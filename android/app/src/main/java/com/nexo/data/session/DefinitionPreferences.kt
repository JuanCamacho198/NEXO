package com.nexo.data.session

import android.content.Context
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.translation.TranslationPolicy
import java.util.Locale

/**
 * Persists the reader's DEFINITION language to an account-scoped
 * SharedPreferences file (T9 of `android-define-selection`).
 *
 * Mirrors [TranslationPreferences] deliberately: the file is
 * `nexo_definition_prefs__{userId}`, resolved at call time from
 * [ActiveUserProvider] (see [AccountPreferences] for why the file name — not the
 * keys — carries isolation).
 *
 * ## Why a sibling store, not a key on the translation store
 *
 * The definition language is a different preference with a different reader
 * (the definition panel AND the settings screen) and a different default ladder
 * (definition language → app UI locale → `en`). One store per preference is
 * what the file name already documents.
 *
 * ## `null` means "follow the app locale"
 *
 * A stored `null` — no value — means the reader never chose, so the language
 * follows the app UI locale. That is why there is no migration and an account
 * that never chose keeps its old behaviour: the absence of a value IS the
 * "follow the locale" state, and [resolve] applies the ladder.
 *
 * With no active account (signed out, or the anonymous session of decision D5)
 * [load] returns `null` and [save] is a no-op: the anonymous path persists
 * nothing.
 *
 * This is NOT the UI language ([AppLanguagePreferences] is global on purpose)
 * and NOT the translation target ([TranslationPreferences]). The three must not
 * be conflated.
 */
class DefinitionPreferences(
    context: Context,
    private val activeUser: ActiveUserProvider,
) {
    private val appContext = context.applicationContext

    /** The active account's file, or `null` when no account owns this state. */
    private fun prefs() = AccountPreferences.open(appContext, PREFS_NAME, activeUser)

    /**
     * The reader's stored definition language, or `null` when they never chose
     * one (follow the app UI locale).
     */
    fun load(): String? = prefs()?.getString(KEY_LANGUAGE, null)

    /**
     * Persists the definition language for the active account. A no-op when no
     * account is active (decision D5). A `null` (or blank) [language] REMOVES
     * the key so the store returns to "follow the app locale".
     */
    fun save(language: String?) {
        val prefs = prefs() ?: return
        val editor = prefs.edit()
        if (language.isNullOrBlank()) {
            editor.remove(KEY_LANGUAGE)
        } else {
            editor.putString(KEY_LANGUAGE, language)
        }
        editor.apply()
    }

    /**
     * The definition language to USE, applying the default ladder: the stored
     * value, else [appLocaleLanguage], else `en`.
     *
     * [appLocaleLanguage] is the app UI locale's language code. A blank stored
     * value and a blank locale both fall through, and an unusable value (a code
     * with no letters) is normalized away rather than passed to the cascade.
     */
    fun resolve(appLocaleLanguage: String?): String {
        load()?.let { stored ->
            val normalized = TranslationPolicy.normalizeLanguage(stored, fallback = "")
            if (normalized.isNotEmpty()) return normalized
        }
        appLocaleLanguage?.let { locale ->
            val normalized = TranslationPolicy.normalizeLanguage(locale, fallback = "")
            if (normalized.isNotEmpty()) return normalized
        }
        return DEFAULT_LANGUAGE
    }

    companion object {
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_definition_prefs"
        private const val KEY_LANGUAGE = "definition_language"

        /** The last rung of the ladder, matching desktop. */
        const val DEFAULT_LANGUAGE = "en"

        /**
         * The app UI locale's language code, for the ladder's middle rung. Kept
         * here so a caller with only a [Context] can reach it without importing
         * an Android locale API of its own.
         */
        fun appLocaleLanguage(): String = Locale.getDefault().language
    }
}

package com.nexo.domain.translation

/**
 * Per-account translation language preferences (T2 of `translation-preferences`).
 *
 * Both fields are `null` by default, and `null` means "let the app decide":
 * the target follows the system locale and the source is derived from the book
 * (or, failing that, the selection itself). This is what keeps the feature
 * working for a user who never opens the settings screen.
 *
 * Deliberately distinct from [com.nexo.data.session.AppLanguagePreferences]:
 * that one is the GLOBAL UI language and must not become per-account.
 */
data class TranslationSettings(
    /** `null` = follow the system locale. */
    val targetLanguage: String? = null,
    /** `null` = resolve from the book language, then the selected text. */
    val sourceLanguage: String? = null,
)

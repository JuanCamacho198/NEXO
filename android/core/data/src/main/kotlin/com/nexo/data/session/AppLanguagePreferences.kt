package com.nexo.data.session

import android.content.Context

class AppLanguagePreferences(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? {
        val code = prefs.getString(KEY_LANG, null)
        return if (code.isNullOrBlank()) null else code
    }

    fun save(langCode: String?) {
        prefs.edit().putString(KEY_LANG, langCode).apply()
    }

    companion object {
        private const val PREFS_NAME = "nexo_app_language"
        private const val KEY_LANG = "app_language_code"
    }
}

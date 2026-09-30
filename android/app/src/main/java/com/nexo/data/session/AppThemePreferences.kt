package com.nexo.data.session

import android.content.Context
import com.nexo.domain.model.ThemeMode

class AppThemePreferences(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode {
        val name = prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try {
            ThemeMode.valueOf(name)
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun save(themeMode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, themeMode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "nextpage_app_theme"
        private const val KEY_THEME = "app_theme_mode"
    }
}

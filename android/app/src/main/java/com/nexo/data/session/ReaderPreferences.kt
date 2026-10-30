package com.nexo.data.session

import android.content.Context
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.domain.session.ActiveUserProvider
import org.json.JSONArray

/**
 * Persists reader settings (font size, theme, line height, layout, custom
 * highlight palette) to an account-scoped SharedPreferences file.
 *
 * Uses enum [name] for stable persistence across enum reordering.
 *
 * ## Per-account (T10 of `aislamiento-datos-por-cuenta`)
 *
 * The file is `nexo_reader_prefs__{userId}` — one file per account, resolved at
 * call time from [ActiveUserProvider] (see [AccountPreferences] for why the file
 * name, not the keys, carries isolation). Before this, two accounts shared
 * `nexo_reader_prefs` and the second inherited the first's font size, theme and
 * highlight palette.
 *
 * With no active account (signed out, or the anonymous session of decision D5)
 * [load] returns defaults and [save] is a no-op: the anonymous path persists
 * nothing.
 *
 * ## Drive tokens are NOT here
 *
 * This class used to also read/write `drive_access_token` /
 * `drive_refresh_token` in plain preferences — a second, unencrypted token
 * surface that nothing called. It was removed: the Drive OAuth tokens live only
 * in [com.nexo.data.remote.drive.EncryptedDriveTokenStore], which is encrypted
 * and now per-account too. Token storage was not downgraded; the plaintext copy
 * was deleted.
 */
class ReaderPreferences(
    context: Context,
    private val activeUser: ActiveUserProvider,
) {
    private val appContext = context.applicationContext

    /** The active account's file, or `null` when no account owns this state. */
    private fun prefs() = AccountPreferences.open(appContext, PREFS_NAME, activeUser)

    /**
     * Load saved settings or return defaults. Returns defaults when no account is
     * active rather than crashing.
     */
    fun load(): ReaderSettings {
        val prefs = prefs() ?: return ReaderSettings()

        val fontSizeName =
            prefs.getString(KEY_FONT_SIZE, FontSizePreset.M.name)
                ?: FontSizePreset.M.name
        val themeName =
            prefs.getString(KEY_THEME, ReaderTheme.DARK.name)
                ?: ReaderTheme.DARK.name
        val lineHeightName =
            prefs.getString(KEY_LINE_HEIGHT, LineHeightPreset.NORMAL.name)
                ?: LineHeightPreset.NORMAL.name

        val alignmentName =
            prefs.getString(KEY_ALIGNMENT, null)
                ?: LayoutPreferences.Alignment.JUSTIFY.name
        val leftMargin = prefs.getInt(KEY_LEFT_MARGIN, 16)
        val rightMargin = prefs.getInt(KEY_RIGHT_MARGIN, 16)

        val customColorsJson = prefs.getString(KEY_PALETTE, null)
        val customColors: List<String>? =
            if (customColorsJson != null) {
                try {
                    val arr = JSONArray(customColorsJson)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        return ReaderSettings(
            fontSize = safeValueOf(FontSizePreset.entries, fontSizeName, FontSizePreset.M),
            theme = safeValueOf(ReaderTheme.entries, themeName, ReaderTheme.DARK),
            lineHeight = safeValueOf(LineHeightPreset.entries, lineHeightName, LineHeightPreset.NORMAL),
            layoutPrefs =
                LayoutPreferences(
                    leftMargin = leftMargin,
                    rightMargin = rightMargin,
                    alignment =
                        safeValueOf(
                            LayoutPreferences.Alignment.entries,
                            alignmentName,
                            LayoutPreferences.Alignment.JUSTIFY,
                        ),
                ),
            customHighlightColors = customColors,
        )
    }

    /**
     * Persist settings for the active account. A no-op when no account is active
     * (decision D5: the anonymous path writes nothing).
     */
    fun save(settings: ReaderSettings) {
        val prefs = prefs() ?: return
        val editor =
            prefs
                .edit()
                .putString(KEY_FONT_SIZE, settings.fontSize.name)
                .putString(KEY_THEME, settings.theme.name)
                .putString(KEY_LINE_HEIGHT, settings.lineHeight.name)
                .putString(KEY_ALIGNMENT, settings.layoutPrefs.alignment.name)
                .putInt(KEY_LEFT_MARGIN, settings.layoutPrefs.leftMargin)
                .putInt(KEY_RIGHT_MARGIN, settings.layoutPrefs.rightMargin)

        if (settings.customHighlightColors != null) {
            val arr = JSONArray()
            for (color in settings.customHighlightColors) arr.put(color)
            editor.putString(KEY_PALETTE, arr.toString())
        } else {
            editor.remove(KEY_PALETTE)
        }

        editor.apply()
    }

    /**
     * Safe enum lookup by name, falling back to a default if not found.
     */
    private inline fun <reified T : Enum<T>> safeValueOf(
        entries: List<T>,
        name: String,
        default: T,
    ): T = entries.find { it.name == name } ?: default

    companion object {
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_reader_prefs"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_THEME = "theme"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_ALIGNMENT = "alignment"
        private const val KEY_LEFT_MARGIN = "left_margin"
        private const val KEY_RIGHT_MARGIN = "right_margin"
        private const val KEY_PALETTE = "highlight_palette"
    }
}

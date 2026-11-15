package com.nexo.data.local

import androidx.room.TypeConverter

/**
 * Room converters for the notification table. Only [i18nParams] needs one:
 * it is a `Map<String, String>?` and Room has no built-in encoder for it.
 *
 * The encoding is a tiny, dependency-free `k=v` list (`\n`-separated, `\` and
 * the two delimiters escaped) so the stored value is stable, human-readable
 * and round-trips keys/values that contain `=`, `\n` or `\`. Enums are stored
 * as their `name` directly by the entity, so no converter is needed for them.
 */
class NotificationConverters {
    @TypeConverter
    fun fromParams(params: Map<String, String>?): String? {
        if (params == null) return null
        return params.entries.joinToString(RECORD_SEPARATOR) { (key, value) ->
            "${escape(key)}$KEY_VALUE_SEPARATOR${escape(value)}"
        }
    }

    @TypeConverter
    fun toParams(raw: String?): Map<String, String>? {
        if (raw == null) return null
        val result = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return result
        for (record in raw.split(RECORD_SEPARATOR)) {
            val separator = record.indexOf(KEY_VALUE_SEPARATOR)
            if (separator < 0) continue
            result[unescape(record.substring(0, separator))] = unescape(record.substring(separator + 1))
        }
        return result
    }

    private fun escape(value: String): String =
        value
            .replace(ESCAPE, "$ESCAPE$ESCAPE")
            .replace(RECORD_SEPARATOR, "$ESCAPE$RECORD_ESCAPE")
            .replace(KEY_VALUE_SEPARATOR, "$ESCAPE$SEPARATOR_ESCAPE")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != ESCAPE_CHAR || index == value.length - 1) {
                out.append(char)
                index++
                continue
            }
            when (value[index + 1]) {
                ESCAPE_CHAR -> out.append(ESCAPE_CHAR)
                RECORD_ESCAPE_CHAR -> out.append(RECORD_SEPARATOR)
                SEPARATOR_ESCAPE_CHAR -> out.append(KEY_VALUE_SEPARATOR)
                else -> out.append(char)
            }
            index += 2
        }
        return out.toString()
    }

    private companion object {
        const val RECORD_SEPARATOR = "\n"
        const val KEY_VALUE_SEPARATOR = "="
        const val ESCAPE = "\\"
        const val RECORD_ESCAPE = "n"
        const val SEPARATOR_ESCAPE = "e"
        const val ESCAPE_CHAR = '\\'
        const val RECORD_ESCAPE_CHAR = 'n'
        const val SEPARATOR_ESCAPE_CHAR = 'e'
    }
}

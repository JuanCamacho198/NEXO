package com.nexo.presentation.util

import android.content.Context
import android.text.format.Formatter

/**
 * Locale-aware, human-readable file size.
 *
 * Delegates to the platform [Formatter.formatFileSize], which reads the
 * context's current configuration (locale included) instead of hardcoding a
 * unit or a number. Kept in one place so the storage screen never formats
 * sizes inline.
 */
fun formatFileSize(
    context: Context,
    bytes: Long,
): String = Formatter.formatFileSize(context, bytes)

package com.nexo.presentation.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun getContentDisplayName(
    context: Context,
    uri: Uri,
): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

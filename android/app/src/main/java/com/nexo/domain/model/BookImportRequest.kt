package com.nexo.domain.model

/**
 * Import request shared by every import entry point.
 *
 * The provenance fields are additive and defaulted so existing callers (SAF
 * picker, share intent, catalog downloads before the addon protocol landed)
 * keep compiling and keep writing the same row as today. Only an addon-sourced
 * import supplies them.
 */
data class BookImportRequest(
    val sourcePath: String,
    val fallbackTitle: String? = null,
    /** Installed addon that produced this book; null for local imports. */
    val sourceAddonId: String? = null,
    /** Frozen protocol id (e.g. `gutenberg:1342`) used for ownership checks. */
    val sourceItemId: String? = null,
    val isbn13: String? = null,
)

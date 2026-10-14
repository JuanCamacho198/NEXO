package com.nexo.presentation.screen.settings

import com.nexo.R

/**
 * U4: static read-only built-in source entries (Fuentes integradas).
 *
 * Gutendex is the metadata/download authority (it powers in-app downloads for
 * public-domain titles, not a rail). The IDLE shelves are author rotation
 * shelves served from Google Books. Open Library is combined search only: no
 * dedicated rail, no IDLE branding (P4 KEEP HIDDEN preserved). Addon toggles
 * untouched; this list never writes registry state.
 */
data class BuiltInSource(
    val name: String,
    val roleResId: Int,
)

/** Static list rendered by [BuiltInSourcesSection]; no backend, no registry. */
val builtInSources =
    listOf(
        BuiltInSource("Gutendex", R.string.settings_addons_builtin_gutendex_role),
        BuiltInSource("Open Library", R.string.settings_addons_builtin_openlibrary_role),
    )

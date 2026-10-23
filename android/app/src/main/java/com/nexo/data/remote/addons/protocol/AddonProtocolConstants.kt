package com.nexo.data.remote.addons.protocol

/**
 * Frozen protocol values. Never change after 3.0.0: id prefixes are persisted
 * in `books.source_item_id`, so editing one is a three-database migration.
 */
object AddonProtocolConstants {
    val TYPE_BOOK: String = "book"

    /** Declared and rejected at install; implemented in a later protocol revision. */
    val RESERVED_TYPES: List<String> = listOf("audiobook", "comic")

    /** Frozen id prefixes understood by book provenance. */
    val ID_PREFIXES: List<String> =
        listOf(
            "gutenberg:",
            "isbn:",
            "openlibrary:",
            "googlebooks:",
            "archive:",
            "standardebooks:",
            "librivox:",
        )

    /** Extra-argument names a catalog may declare. */
    val EXTRA_NAMES: List<String> = listOf("search", "skip", "genre", "lang")

    /**
     * Client-bundled payload adapters. Shapes are explicitly not portable: they
     * are the price of supporting third-party APIs that will never speak the
     * protocol.
     */
    val SHAPES: List<String> = AddonShape.entries.map { it.wire }

    /** `nexo` is NEXO-only, and is capped so it can never bloat the manifest. */
    const val MAX_NEXO_CHARS: Int = 8 * 1024
}

package com.nexo.domain.discover

import java.time.LocalDate

/**
 * One curated author shelf: the S2 string-resource key plus its catalog search
 * term. `titleKey` is a stable, unresolved key — the UI layer maps it to a
 * string resource; the domain never carries localized text.
 */
data class AuthorEntry(
    val slug: String,
    val titleKey: String,
    val term: String,
)

/** Rows shown by the Discover author rail rotation. */
const val DISCOVER_RAIL_COUNT = 3

/**
 * Curated author rotation, best-vetted first. Mirrors
 * `desktop/src/lib/features/discover/railRotation.ts` verbatim, including the
 * order, slug and search term. Public-domain-era authors are deliberate: the
 * download path resolves from Gutendex and every one of these has a Project
 * Gutenberg edition.
 */
val AUTHOR_ROTATION: List<AuthorEntry> =
    listOf(
        AuthorEntry("doyle", "discover_rail_author_doyle", "arthur conan doyle"),
        AuthorEntry("poe", "discover_rail_author_poe", "edgar allan poe"),
        AuthorEntry("wilde", "discover_rail_author_wilde", "oscar wilde"),
        AuthorEntry("verne", "discover_rail_author_verne", "jules verne"),
        AuthorEntry("kafka", "discover_rail_author_kafka", "franz kafka"),
        AuthorEntry("dickens", "discover_rail_author_dickens", "charles dickens"),
        AuthorEntry("london", "discover_rail_author_london", "jack london"),
        AuthorEntry("wells", "discover_rail_author_wells", "h g wells"),
        AuthorEntry("twain", "discover_rail_author_twain", "mark twain"),
        AuthorEntry("melville", "discover_rail_author_melville", "herman melville"),
    )

/**
 * Index into [AUTHOR_ROTATION] of the day's first (top) shelf.
 *
 * Formula: `(dayOfYear - 1) mod length`. The minus one anchors the very first
 * local calendar day at index 0 instead of already slid forward. The index
 * advances by exactly one slot per local calendar day, is stable for a whole
 * local day, and wraps at the list length. The result is normalized into
 * `[0, length)` so no input can escape the list bounds.
 */
fun startIndexFor(date: LocalDate): Int {
    val dayOfYear = date.dayOfYear
    val length = AUTHOR_ROTATION.size
    return (((dayOfYear - 1) % length) + length) % length
}

/**
 * The day's shelves: [count] consecutive entries starting at [startIndexFor],
 * wrapping at the list end. Consecutive offsets are distinct while
 * `count <= AUTHOR_ROTATION.size`, so the rails of a day never repeat an author.
 */
fun entriesFor(date: LocalDate, count: Int = DISCOVER_RAIL_COUNT): List<AuthorEntry> {
    val length = AUTHOR_ROTATION.size
    require(count in 1..length) { "invalid author rail count: $count for $length authors" }
    val start = startIndexFor(date)
    return List(count) { offset -> AUTHOR_ROTATION[(start + offset) % length] }
}

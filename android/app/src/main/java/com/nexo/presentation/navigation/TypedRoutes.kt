package com.nexo.presentation.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the two destinations that carry arguments.
 *
 * Every property has a default, so all of them become Navigation *query*
 * arguments (`bookId`, `bookPath`, `bookFormat` / `sectionTitle`, `sort`,
 * `sourceId`) and both types are safe to navigate to bare. `@SerialName` keeps
 * the generated route path identical to the hand-written pattern these types
 * replace, which makes the destination route strings byte-for-byte:
 *
 * - `reader?bookId={bookId}&bookPath={bookPath}&bookFormat={bookFormat}`
 * - `discover/section?sectionTitle={sectionTitle}&sort={sort}&sourceId={sourceId}&term={term}`
 *
 * Percent-encoding is owned by Navigation itself (`NavType.StringType` encodes
 * every query value and decodes it back), which is what replaces the deleted
 * `Reader.routeFor`/`encodeRouteParam` and `encodeQueryValue` helpers.
 */
@Serializable
@SerialName("reader")
data class ReaderRoute(
    val bookId: String = "",
    val bookPath: String? = null,
    val bookFormat: String = "epub",
)

/**
 * "Ver todo" section list. `sectionTitle` is already-localized copy and exactly
 * one of `sort` / `topicTerm` / `sourceId` is non-blank so the screen knows which
 * paging seam to use; every selector defaults to blank (the previous `""` query
 * defaults).
 *
 * `topicTerm` is a Gutendex `?topic=` term and is kept apart from `term`: a topic
 * shelf pages its topic selector, while `term` narrows a per-source keyword list.
 * Collapsing them is what made "Ver todo" answer 6 books for a 3 530-book shelf.
 */
@Serializable
@SerialName("discover/section")
data class DiscoverSectionRoute(
    val sectionTitle: String = "",
    val sort: String = "",
    val sourceId: String = "",
    val term: String = "",
    val topicTerm: String = "",
)

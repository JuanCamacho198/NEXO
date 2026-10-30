package com.nexo.domain.discover

import java.time.LocalDate

/**
 * One curated topic shelf: the S2 string-resource key plus its Gutendex `topic`
 * term. `titleKey` is a stable, unresolved key — the UI layer maps it to a
 * string resource; the domain never carries localized text.
 */
data class TopicEntry(
    val slug: String,
    val titleKey: String,
    val term: String,
)

/**
 * Topic shelves shown by the Discover rail rotation.
 *
 * Two, matching desktop `DISCOVER_TOPIC_RAIL_COUNT`: the full IDLE rail plan is
 * four shelves (two topics + Gutendex popular + Standard Ebooks), and the
 * rotation only owns the first two slots.
 */
const val DISCOVER_RAIL_COUNT = 2

/**
 * Curated topic rotation, most plentiful first. Mirrors
 * `desktop/src/lib/features/discover/railRotation.ts` verbatim, including the
 * order, slug and term.
 *
 * Every term was probed live against
 * `https://gutendex.com/books/?topic=<term>&page=1` before it entered this
 * table; the trailing numbers are the upstream total `count` the probe returned:
 *
 * history 23577, adventure 9005, travel 5620, poetry 5599, philosophy 3538,
 * science fiction 3530, drama 3249, romance 3050, mystery 2910, horror 283.
 *
 * All ten answered HTTP 200 with a full first page, so each can fill the widest
 * rail. `horror` is deliberately LAST despite being alive: it is the thinnest
 * shelf here — an acceptable member, never a lead.
 *
 * The first two are the required shelves, `science fiction` and `adventure`, and
 * the rotation is an offset into this same list, so neither can disappear.
 *
 * Why a closed table and not a free string: an unrecognized upstream `sort`
 * literal hangs the request (see `CatalogFeaturedSort`), and `topic` is the same
 * class of parameter. The ViewModel passes a member of this list and nothing
 * else. Do NOT add a term without re-running the probe and updating the counts.
 */
val TOPIC_ROTATION: List<TopicEntry> =
    listOf(
        TopicEntry("science-fiction", "discover_rail_topic_science_fiction", "science fiction"),
        TopicEntry("adventure", "discover_rail_topic_adventure", "adventure"),
        TopicEntry("history", "discover_rail_topic_history", "history"),
        TopicEntry("travel", "discover_rail_topic_travel", "travel"),
        TopicEntry("poetry", "discover_rail_topic_poetry", "poetry"),
        TopicEntry("philosophy", "discover_rail_topic_philosophy", "philosophy"),
        TopicEntry("drama", "discover_rail_topic_drama", "drama"),
        TopicEntry("romance", "discover_rail_topic_romance", "romance"),
        TopicEntry("mystery", "discover_rail_topic_mystery", "mystery"),
        TopicEntry("horror", "discover_rail_topic_horror", "horror"),
    )

/**
 * Index into [TOPIC_ROTATION] of the day's first (top) shelf.
 *
 * Formula: `(dayOfYear - 1) mod length`. The minus one anchors the very first
 * local calendar day at index 0 instead of already slid forward. The index
 * advances by exactly one slot per local calendar day, is stable for a whole
 * local day, and wraps at the list length. The result is normalized into
 * `[0, length)` so no input can escape the list bounds.
 */
fun startIndexFor(date: LocalDate): Int {
    val dayOfYear = date.dayOfYear
    val length = TOPIC_ROTATION.size
    return (((dayOfYear - 1) % length) + length) % length
}

/**
 * The day's shelves: [count] consecutive entries starting at [startIndexFor],
 * wrapping at the list end. Consecutive offsets are distinct while
 * `count <= TOPIC_ROTATION.size`, so the two rails of a day never repeat a topic.
 */
fun entriesFor(
    date: LocalDate,
    count: Int = DISCOVER_RAIL_COUNT,
): List<TopicEntry> {
    val length = TOPIC_ROTATION.size
    require(count in 1..length) { "invalid topic rail count: $count for $length topics" }
    val start = startIndexFor(date)
    return List(count) { offset -> TOPIC_ROTATION[(start + offset) % length] }
}

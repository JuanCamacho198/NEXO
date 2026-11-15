package com.nexo.domain.discover

/** Screen-level status for the Discover search flow. */
enum class DiscoverStatus {
    IDLE,
    LOADING,
    LOADING_MORE,
    LOADED,
    EMPTY,
    ERROR,
    OFFLINE,
}

/**
 * Rails sharing one upstream. When every rail of a group fails together the
 * reader gets ONE notice instead of an identical inline error per rail: the
 * author shelves share the Google Books quota and fail as a unit. Standard
 * Ebooks is a group of one, so its grouped notice and its inline error agree.
 *
 * A group never names its rails: `DiscoverViewModel.retryRailGroup` rebuilds
 * them from the same deterministic plan, so a retry re-resolves exactly its own
 * group and never refetches a shelf that worked.
 */
enum class DiscoverRailGroup {
    GoogleBooks,
    Gutendex,
    StandardEbooks,
}

/** Detail pane status, independent from the search-list status. */
enum class DiscoverDetailStatus {
    CLOSED,
    LOADING,
    LOADED,
    NOT_FOUND,
    ERROR,
}

/**
 * In-memory source narrowing for the results grid. [AllSources] is the default;
 * [Source] pins exactly one `CatalogSourceInfo.sourceId`. A filter whose source
 * disappears mid-session (addon disabled/uninstalled) degrades back to
 * [AllSources] instead of throwing or rendering an empty phantom filter.
 */
sealed interface DiscoverSourceFilter {
    data object AllSources : DiscoverSourceFilter

    data class Source(
        val sourceId: String,
    ) : DiscoverSourceFilter
}

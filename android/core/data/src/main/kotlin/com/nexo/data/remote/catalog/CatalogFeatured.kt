package com.nexo.data.remote.catalog

/**
 * Closed domain of featured orderings: every entry maps to a verified upstream
 * literal, so no caller can ever pass a raw `sort` string through to Gutendex.
 *
 * Why closed: an unrecognized `sort` value makes the upstream call hang (live
 * HTTP probe), so the seam deliberately has no string passthrough.
 *
 * - [POPULAR] -> `sort=popular` (all-time download count, HTTP 200 verified).
 * - [NEWEST]  -> `sort=descending` (newest-by-id, HTTP 200 verified). Note this
 *   is *not* week-scoped: Gutendex exposes no time-windowed ordering.
 */
// CatalogFeaturedSort lives in :core:domain; re-exported below for data-layer callers.

typealias CatalogFeaturedSort = com.nexo.domain.discover.CatalogFeaturedSort

package com.nexo.domain.discover

import com.nexo.domain.model.CatalogBook
import com.nexo.domain.model.CatalogErrorCode

/**
 * Fail-closed state of one IDLE featured rail.
 *
 * A rail that cannot be served (no capability, upstream error, empty page) is
 * [Hidden] and its section is not composed at all — never an empty rail, never a
 * placeholder header. There is deliberately no screen-level rail status: each
 * rail resolves on its own.
 */
sealed interface DiscoverRailState {
    data object Hidden : DiscoverRailState

    data object Loading : DiscoverRailState

    /**
     * An upstream failure kept in place instead of hidden: the section renders
     * its own inline error and retry, and a source whose every rail failed adds
     * ONE grouped notice below the shelves (never a second copy per rail).
     *
     * [sourceId] identifies the owning source, so a source filter can still
     * attribute the failure to its shelf.
     */
    data class Error(
        val code: CatalogErrorCode,
        val sourceId: String,
    ) : DiscoverRailState

    data class Loaded(
        val sectionTitleRes: Int,
        /** Sort selector of a featured rail; null for addon and topic shelves. */
        val sort: String?,
        val sourceId: String?,
        val books: List<CatalogBook>,
        val totalCount: Int,
        /** Addon display name for a per-addon rail; null for featured rails. */
        val addonName: String? = null,
        /**
         * Gutendex topic term for a topic shelf; null for featured and addon
         * rails. Carried so "See all" can page the same `topic` query the shelf
         * used.
         */
        val topicTerm: String? = null,
    ) : DiscoverRailState
}

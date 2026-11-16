package com.nexo.testutil

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * In-memory [PagingSource] for unit tests that supports `Refresh` and `Append`
 * loads over a fixed list. Mirrors the contract of Room's generated
 * PagingSource so we can exercise the [androidx.paging.Pager] code path
 * without an Android device.
 *
 * Page indices are 0-based; the next key is `currentIndex + 1` until the
 * end of the list, then `null`.
 */
@Suppress("UNCHECKED_CAST")
class PagedListPagingSource<T : Any>(
    private val items: List<T>,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return 0
        val closest = state.closestPageToPosition(anchor) ?: return 0
        return closest.prevKey?.plus(1) ?: closest.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val key = params.key ?: 0
        val from = key * params.loadSize
        if (from >= items.size) {
            return LoadResult.Page(
                data = emptyList(),
                prevKey = if (key == 0) null else key - 1,
                nextKey = null,
            )
        }
        val to = minOf(from + params.loadSize, items.size)
        val page = items.subList(from, to)
        val prevKey = if (key == 0) null else key - 1
        val nextKey = if (to >= items.size) null else key + 1
        return LoadResult.Page(
            data = page,
            prevKey = prevKey,
            nextKey = nextKey,
        )
    }
}

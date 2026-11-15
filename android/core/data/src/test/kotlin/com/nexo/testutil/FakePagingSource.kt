package com.nexo.testutil

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Test-only [PagingSource] that yields a fixed list of items in a single page.
 *
 * Use this in unit tests to provide a deterministic PagingSource without
 * depending on Room's generated PagingSource (which requires an Android
 * device and a real database to instantiate).
 */
class FakePagingSource<T : Any>(
    private val items: List<T>,
) : PagingSource<Int, T>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
        LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = null,
        )

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}

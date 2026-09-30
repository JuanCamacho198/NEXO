package com.nexo.presentation.viewmodel.library

import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.UserBookRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CatalogPollingHolder(
    private val catalogSync: SupabaseBookCatalogSync,
    private val ioDispatcher: CoroutineDispatcher,
    private val onFastList: (List<UserBookRow>) -> Unit,
    private val onEnriched: (List<UserBookRow>) -> Unit,
    private val onLoadingDone: () -> Unit,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                pollLoop()
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun isActive(): Boolean = job?.isActive == true

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            currentCoroutineContext().ensureActive()
            try {
                catalogSync
                    .getDownloadableBooks()
                    .onSuccess { books ->
                        currentCoroutineContext().ensureActive()
                        onFastList(books)
                        onLoadingDone()
                        if (books.any { it.fileSize == null }) {
                            val userId = catalogSync.currentUserId()
                            if (userId != null) {
                                coroutineScope {
                                    val deferred =
                                        async(ioDispatcher) {
                                            currentCoroutineContext().ensureActive()
                                            catalogSync.enrichFileSizes(books, userId)
                                        }
                                    val enriched = deferred.await()
                                    currentCoroutineContext().ensureActive()
                                    if (enriched != books) {
                                        onEnriched(enriched)
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        onLoadingDone()
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                onLoadingDone()
            }
            delay(CATALOG_POLL_INTERVAL_MS)
            currentCoroutineContext().ensureActive()
        }
    }

    companion object {
        const val CATALOG_POLL_INTERVAL_MS = 30_000L
    }
}

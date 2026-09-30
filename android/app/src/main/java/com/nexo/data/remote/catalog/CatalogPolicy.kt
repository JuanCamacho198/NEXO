package com.nexo.data.remote.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Courtesy + resilience policy for public catalog sources.
 * Lives in the provider layer (never UI) so both platforms share semantics.
 * Mirrors desktop `services/catalog/policy.ts` constant-for-constant.
 */
const val DEFAULT_PAGE_SIZE = 24
const val MIN_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 32

/** Trailing-edge debounce window for burst searches (spec: 300–400ms). */
const val DEBOUNCE_MS = 350L

/** Minimum gap between Open Library calls (anonymous courtesy limit). */
const val OL_MIN_GAP_MS = 1000L

/** Exactly one delayed retry on 429/5xx — never more without delay. */
const val MAX_DELAYED_RETRIES = 1
const val RETRY_BASE_DELAY_MS = 800L

/** HTTP status bands shared by transports, registry and providers. */
const val HTTP_OK_MIN = 200
const val HTTP_OK_MAX = 299
const val HTTP_NOT_FOUND = 404
const val HTTP_TOO_MANY_REQUESTS = 429
const val HTTP_SERVER_ERROR_MIN = 500
const val HTTP_SERVER_ERROR_MAX = 599

const val ANDROID_USER_AGENT = "NEXO/Android (contact: TBD)"

fun buildUserAgent(platform: String): String = "NEXO/$platform (contact: TBD)"

/** Clamp a requested page size into the contractual 20–32 window. */
fun clampPageSize(requested: Int): Int = requested.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)

/** Clamp a requested page size into the contractual 20–32 window. */
fun clampPageSize(requested: Double): Int {
    if (!requested.isFinite()) return DEFAULT_PAGE_SIZE
    return requested.toInt().coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
}

fun shouldRetryStatus(status: Int): Boolean = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR_MIN

/** True for HTTP 2xx responses. */
fun Int.isHttpSuccess(): Boolean = this in HTTP_OK_MIN..HTTP_OK_MAX

/** Exponential backoff delay for the single delayed retry. */
fun backoffDelayMs(attempt: Int): Long = RETRY_BASE_DELAY_MS * (1L shl attempt.coerceAtLeast(0))

/** Enforces a minimum gap between calls (Open Library 1 req/s courtesy). */
class RateLimiter(
    private val minGapMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var lastCall = 0L

    suspend fun waitForSlot() {
        mutex.withLock {
            val wait = (lastCall + minGapMs - now()).coerceAtLeast(0)
            if (wait > 0) delay(wait)
            lastCall = now()
        }
    }
}

/**
 * Trailing-edge debouncer for provider search: rapid calls reset the window
 * and only the latest query issues network I/O; every caller resolves
 * with that latest result. Mirrors desktop `createSearchDebouncer`.
 */
class SearchDebouncer<T>(
    private val scope: CoroutineScope,
    private val windowMs: Long = DEBOUNCE_MS,
    private val execute: suspend (query: String, page: Int) -> T,
) {
    private val mutex = Mutex()
    private var pending: MutableList<PendingSearch<T>> = mutableListOf()
    private var job: Job? = null

    private data class PendingSearch<T>(
        val query: String,
        val page: Int,
        val result: CompletableDeferred<T> = CompletableDeferred(),
    )

    suspend fun search(
        query: String,
        page: Int,
    ): T {
        val entry = PendingSearch<T>(query, page)
        mutex.withLock {
            pending.add(entry)
            job?.cancel()
            // Child of the injected scope: production passes a Default-dispatcher
            // scope, tests pass a TestScope so `delay` below is virtual time.
            job =
                scope.launch {
                    delay(windowMs)
                    val batch: List<PendingSearch<T>>
                    mutex.withLock {
                        batch = pending.toList()
                        pending = mutableListOf()
                    }
                    val latest = batch.last()
                    try {
                        val result = execute(latest.query, latest.page)
                        batch.forEach { it.result.complete(result) }
                    } catch (err: Throwable) {
                        batch.forEach { it.result.completeExceptionally(err) }
                    }
                }
        }
        return entry.result.await()
    }

    suspend fun cancel() {
        mutex.withLock {
            job?.cancel()
            job = null
            pending = mutableListOf()
        }
    }
}

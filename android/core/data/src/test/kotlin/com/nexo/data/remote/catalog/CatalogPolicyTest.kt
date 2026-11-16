package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline mirror of the desktop `catalog.test.ts` policy suite. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPolicyTest {
    @Test fun clampPageSize_keepsContractualWindowWithDefault24() {
        assertEquals(24, clampPageSize(24))
        assertEquals(20, clampPageSize(4))
        assertEquals(32, clampPageSize(200))
        assertEquals(24, clampPageSize(Double.NaN))
    }

    @Test fun shouldRetryStatus_retriesOnly429And5xx() {
        assertTrue(shouldRetryStatus(429))
        assertTrue(shouldRetryStatus(503))
        assertFalse(shouldRetryStatus(404))
        assertFalse(shouldRetryStatus(400))
    }

    @Test fun backoffDelayMs_growsExponentiallyFrom800msBase() {
        assertEquals(800L, backoffDelayMs(0))
        assertEquals(1600L, backoffDelayMs(1))
    }

    @Test fun buildUserAgent_identifiesAndroidPlatformWithTbdContact() {
        assertEquals("NEXO/Android (contact: TBD)", ANDROID_USER_AGENT)
        assertEquals("NEXO/Android (contact: TBD)", buildUserAgent("Android"))
    }

    @Test fun searchDebouncer_onlyLatestBurstQueryHitsIo() =
        runTest {
            val executed = mutableListOf<String>()
            val debouncer =
                SearchDebouncer(backgroundScope, 350L) { query: String, _: Int ->
                    executed.add(query)
                    "result:$query"
                }
            val results = mutableListOf<String>()
            val j1 = launch { results.add(debouncer.search("first", 1)) }
            val j2 = launch { results.add(debouncer.search("second", 1)) }
            val j3 = launch { results.add(debouncer.search("third", 1)) }
            runCurrent()
            advanceTimeBy(400)
            j1.join()
            j2.join()
            j3.join()
            assertEquals(listOf("third"), executed)
            assertEquals(listOf("result:third", "result:third", "result:third"), results)
        }

    @Test fun rateLimiter_enforcesOneSecondOlGap() =
        runTest {
            val limiter = RateLimiter(OL_MIN_GAP_MS) { testScheduler.currentTime }
            limiter.waitForSlot()
            limiter.waitForSlot()
            // Each slot waited out the 1s courtesy gap in virtual time.
            assertEquals(2000L, testScheduler.currentTime)
        }
}

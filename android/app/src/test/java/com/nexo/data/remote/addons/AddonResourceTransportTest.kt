package com.nexo.data.remote.addons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

/** Holds every request inside the transport until the test releases it. */
private class GatedTransport : AddonHttpTransport {
    private val active = mutableMapOf<String, Int>()

    val peak = mutableMapOf<String, Int>()
    val calls = mutableListOf<String>()

    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    override suspend fun fetch(url: String): AddonResource {
        val host = URI(url).host ?: url
        synchronized(active) {
            calls += url
            val now = (active[host] ?: 0) + 1
            active[host] = now
            peak[host] = maxOf(peak[host] ?: 0, now)
        }
        try {
            gate.await()
            return AddonResource(200, "application/json", "{}".toByteArray(Charsets.UTF_8))
        } finally {
            synchronized(active) { active[host] = (active[host] ?: 1) - 1 }
        }
    }
}

private class FixedTransport(
    private val resource: AddonResource,
) : AddonHttpTransport {
    override suspend fun fetch(url: String): AddonResource = resource
}

class AddonResourceTransportTest {
    @Test
    fun `caps a single host at three concurrent requests`() =
        runTest {
            val transport = GatedTransport()
            val subject = AddonProtocolTransport(transport, maxConcurrentPerHost = 3)
            val pending =
                (1..6).map { index ->
                    async { subject.fetch("https://same.example/item$index", timeoutMs = 0) }
                }

            advanceUntilIdle()
            assertEquals(3, transport.peak["same.example"])
            assertEquals(3, transport.calls.size)

            transport.release()
            pending.awaitAll()
            assertEquals(3, transport.peak["same.example"])
            assertEquals(6, transport.calls.size)
        }

    @Test
    fun `one host's budget never blocks another host`() =
        runTest {
            val transport = GatedTransport()
            val subject = AddonProtocolTransport(transport, maxConcurrentPerHost = 3)
            val pending =
                (1..4).map { index ->
                    async { subject.fetch("https://busy.example/item$index", timeoutMs = 0) }
                } +
                    (1..2).map { index ->
                        async { subject.fetch("https://quiet.example/item$index", timeoutMs = 0) }
                    }

            advanceUntilIdle()
            assertEquals(3, transport.peak["busy.example"])
            assertEquals(2, transport.peak["quiet.example"])

            transport.release()
            pending.awaitAll()
        }

    @Test
    fun `non-2xx answers keep the status and carry no body`() =
        runTest {
            val subject =
                AddonProtocolTransport(
                    FixedTransport(AddonResource(404, "application/json", """{"streams":[]}""".toByteArray())),
                )
            val response = subject.fetch("https://a.example/stream/book/x.json", timeoutMs = 1_000)
            assertEquals(404, response.status)
            assertNull(response.body)
        }

    @Test
    fun `a 2xx body is parsed as JSON and the cache directive passes through`() =
        runTest {
            val subject =
                AddonProtocolTransport(
                    FixedTransport(
                        AddonResource(
                            status = 200,
                            contentType = "application/json",
                            body = """{"metas":[]}""".toByteArray(Charsets.UTF_8),
                            cacheControl = "public, max-age=60",
                        ),
                    ),
                )
            val response = subject.fetch("https://a.example/catalog/book/main.json", timeoutMs = 1_000)
            assertEquals(200, response.status)
            assertEquals("""{"metas":[]}""", response.body.toString())
            assertEquals("public, max-age=60", response.cacheControl)
        }

    @Test
    fun `an unparseable body is a payload defect, not a network one`() =
        runTest {
            val subject = AddonProtocolTransport(FixedTransport(AddonResource(200, "application/json", "not json".toByteArray())))
            try {
                subject.fetch("https://a.example/meta/book/x.json", timeoutMs = 1_000)
                fail("expected JSONException")
            } catch (_: JSONException) {
            }
        }

    @Test
    fun `the transport owns the deadline and signals its own timeout`() =
        runTest {
            val slow =
                object : AddonHttpTransport {
                    override suspend fun fetch(url: String): AddonResource {
                        delay(60_000)
                        return AddonResource(200, "application/json", "{}".toByteArray())
                    }
                }
            val subject = AddonProtocolTransport(slow)
            try {
                subject.fetch("https://a.example/meta/book/x.json", timeoutMs = 1_000)
                fail("expected a timeout")
            } catch (_: TimeoutCancellationException) {
            }
        }
}

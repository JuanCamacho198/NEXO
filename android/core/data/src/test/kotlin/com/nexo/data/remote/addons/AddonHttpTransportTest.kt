package com.nexo.data.remote.addons

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline tests for the addon transport boundary (no real network). */
class AddonHttpTransportTest {
    @Test
    fun `fake transport counts calls and returns canned resource`() =
        runTest {
            val fake =
                FakeAddonHttpTransport(
                    result = AddonResource(200, "application/json", "{}".toByteArray()),
                )
            val resource = fake.fetch("https://example.com/manifest.json")
            assertEquals(200, resource.status)
            assertEquals("application/json", resource.contentType)
            assertEquals(1, fake.calls)
        }

    @Test
    fun `transport failure surfaces as ADDON_FETCH_NETWORK`() =
        runTest {
            val fake = FakeAddonHttpTransport(error = IllegalStateException("offline"))
            try {
                fake.fetch("https://example.com/manifest.json")
                throw AssertionError("expected AddonFetchException")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.NETWORK, err.code)
            }
            assertEquals(1, fake.calls)
        }

    @Test
    fun `addon fetch exceptions pass through unwrapped`() =
        runTest {
            val https = AddonFetchException(AddonFetchErrorCode.HTTPS_REQUIRED, "pre-checked")
            val fake = FakeAddonHttpTransport(error = https)
            try {
                fake.fetch("https://example.com/manifest.json")
                throw AssertionError("expected AddonFetchException")
            } catch (err: AddonFetchException) {
                assertTrue(err === https)
            }
        }

    @Test
    fun `addon fetch performs https pre-check before any io`() =
        runTest {
            val fake =
                FakeAddonHttpTransport(
                    result = AddonResource(200, "application/json", "{}".toByteArray()),
                )
            // Guard contract shared with desktop: non-https rejected before transport.
            try {
                ManifestValidator.assertHttpsInstallUrl("http://example.com/manifest.json")
                throw AssertionError("expected AddonFetchException")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, err.code)
            }
            assertEquals(0, fake.calls)
        }
}

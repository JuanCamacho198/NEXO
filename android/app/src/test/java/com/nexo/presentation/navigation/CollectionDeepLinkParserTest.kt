package com.nexo.presentation.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure parse matrix for `nexo://collection?url=<https-url>`: the canonical
 * collection link (legacy `nextpage://collection` still resolves). Non-collection
 * URIs — including the single-addon `install` host — must not be claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionDeepLinkParserTest {
    @Test
    fun `valid collection uri parses to the https collection url`() {
        val url =
            CollectionDeepLinkParser.parse(
                Uri.parse("nexo://collection?url=https%3A%2F%2Fexample.com%2Fcollection.json"),
            )
        assertEquals("https://example.com/collection.json", url)
    }

    @Test
    fun `accepts the legacy scheme`() {
        assertEquals(
            "https://example.com/collection.json",
            CollectionDeepLinkParser.parse(
                Uri.parse("nextpage://collection?url=https%3A%2F%2Fexample.com%2Fcollection.json"),
            ),
        )
    }

    @Test
    fun `non-collection hosts are not collection uris`() {
        for (
        raw in
        listOf(
            "nexo://install?url=https%3A%2F%2Fexample.com%2Fmanifest.json",
            "nextpage://auth/callback?access_token=t",
            "nexo://auth/reset-password",
            "com.googleusercontent.apps.abc123:/oauth2redirect",
            "nexo://addons?url=https://example.com/collection.json",
        )
        ) {
            assertFalse("expected non-collection: $raw", CollectionDeepLinkParser.isCollectionUri(Uri.parse(raw)))
            assertNull("expected null parse: $raw", CollectionDeepLinkParser.parse(Uri.parse(raw)))
        }
        assertFalse(CollectionDeepLinkParser.isCollectionUri(null))
        assertNull(CollectionDeepLinkParser.parse(null))
    }

    @Test
    fun `missing or blank url param yields null but stays a collection uri`() {
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection")))
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection?other=1")))
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection?url=")))
        assertTrue(CollectionDeepLinkParser.isCollectionUri(Uri.parse("nexo://collection")))
    }

    @Test
    fun `non-https collection url is rejected before any fetch`() {
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection?url=http://example.com/c.json")))
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection?url=file:///tmp/c.json")))
        assertNull(CollectionDeepLinkParser.parse(Uri.parse("nexo://collection?url=not%20a%20url")))
        assertTrue(CollectionDeepLinkParser.isCollectionUri(Uri.parse("nexo://collection?url=http://x.test/c.json")))
    }
}

package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure parse matrix for `nextpage://install?url=<https-url>` (task B2 RED).
 * Non-install URIs (auth, drive) are untouched → the parser must not claim them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallDeepLinkParserTest {
    @Test
    fun `valid install uri parses to https manifest url`() {
        val url =
            InstallDeepLinkParser.parse(
                Uri.parse("nextpage://install?url=https%3A%2F%2Fexample.com%2Fmanifest.json"),
            )
        assertEquals("https://example.com/manifest.json", url)
    }

    @Test
    fun `non-install hosts are not install uris`() {
        for (raw in listOf(
            "nextpage://auth/callback?access_token=t",
            "nextpage://auth/reset-password",
            "nextpage://auth/confirm#fragment",
            "com.googleusercontent.apps.abc123:/oauth2redirect",
            "nextpage://addons?url=https://example.com/m.json",
        )) {
            assertFalse(
                "expected non-install: $raw",
                InstallDeepLinkParser.isInstallUri(Uri.parse(raw)),
            )
            assertNull("expected null parse: $raw", InstallDeepLinkParser.parse(Uri.parse(raw)))
        }
    }

    @Test
    fun `missing url param yields null`() {
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install")))
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?other=1")))
    }

    @Test
    fun `non-https url param is rejected before any fetch`() {
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?url=http://example.com/m.json")))
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?url=file:///tmp/m.json")))
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?url=ftp://example.com/m.json")))
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?url=not%20a%20url")))
    }

    @Test
    fun `blank url param yields null`() {
        assertNull(InstallDeepLinkParser.parse(Uri.parse("nextpage://install?url=")))
    }

    @Test
    fun `isInstallUri claims invalid install uris so the controller can surface the error`() {
        // Routing contract (verify D1): host=install URIs are claimed even when
        // the url param is missing/non-https, so the controller can show the
        // https-required error instead of the link being dropped silently.
        assertTrue(InstallDeepLinkParser.isInstallUri(Uri.parse("nextpage://install?url=http://x.test/m.json")))
        assertTrue(InstallDeepLinkParser.isInstallUri(Uri.parse("nextpage://install")))
    }

    @Test
    fun `isInstallUri matches exactly the install host`() {
        assertTrue(InstallDeepLinkParser.isInstallUri(Uri.parse("nextpage://install?url=https://x.test/m.json")))
        assertTrue(InstallDeepLinkParser.isInstallUri(Uri.parse("nextpage://install")))
        assertFalse(InstallDeepLinkParser.isInstallUri(null))
    }

    @Test
    fun `https required error code is the shared additive code`() {
        // Guard: parser rejects via the shared AddonFetchError set (no new codes).
        try {
            com.nexo.data.remote.addons.ManifestValidator
                .assertHttpsInstallUrl("http://example.com")
            throw AssertionError("expected HTTPS_REQUIRED")
        } catch (err: AddonFetchException) {
            assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, err.code)
        }
    }
}

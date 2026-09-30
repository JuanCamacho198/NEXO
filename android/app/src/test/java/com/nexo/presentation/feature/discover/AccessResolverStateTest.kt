package com.nexo.presentation.feature.discover

import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.addonSource
import com.nexo.domain.access.resolveAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5: `mapAccessState` matrix — loading is owned by the ViewModel before
 * the fetch; the mapping covers loaded / empty / error / offline plus the
 * consent gate, and the consent-gated UI state contract.
 */
class AccessResolverStateTest {
    private companion object {
        const val ADDON_ID = "abcdef1234567890"
        const val PD_DOWNLOAD_URL = "https://www.gutenberg.org/cache/epub/1342/pg1342.epub"
    }

    private fun book(
        id: String = "gutendex:1342",
        provider: String = BUILTIN_GUTENDEX,
        downloadUrl: String? = PD_DOWNLOAD_URL,
        isPublicDomain: Boolean? = true,
    ): CatalogBook =
        CatalogBook(
            id = id,
            provider = provider,
            title = "Pride and Prejudice",
            authors = listOf("Jane Austen"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = downloadUrl,
            isPublicDomain = isPublicDomain,
        )

    private fun addonBook(): CatalogBook =
        book(
            id = "addon:$ADDON_ID:1",
            provider = addonSource(ADDON_ID),
            downloadUrl = null,
            isPublicDomain = null,
        )

    @Test
    fun offline_winsOverEveryOtherSignal() {
        val state =
            mapAccessState(
                isOnline = false,
                consentRequiredAddonId = ADDON_ID,
                hasConsent = false,
                access = resolveAccess(book()),
                failed = true,
            )
        assertEquals(AccessResolverState.Offline, state)
    }

    @Test
    fun unconsentedAddonBook_gatesToConsentRequiredWithoutResolve() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = ADDON_ID,
                hasConsent = false,
                access = resolveAccess(addonBook()),
                failed = false,
            )
        assertEquals(AccessResolverState.ConsentRequired(ADDON_ID), state)
    }

    @Test
    fun consentedAddonBook_withGenericLinks_loads() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = ADDON_ID,
                hasConsent = true,
                access = resolveAccess(addonBook()),
                failed = false,
            )
        assertTrue(state is AccessResolverState.Loaded)
    }

    @Test
    fun failedFetch_mapsToError() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = null,
                hasConsent = true,
                access = null,
                failed = true,
            )
        assertEquals(AccessResolverState.Error, state)
    }

    @Test
    fun nullAccessWithoutFailure_mapsToError() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = null,
                hasConsent = true,
                access = null,
                failed = false,
            )
        assertEquals(AccessResolverState.Error, state)
    }

    @Test
    fun optionlessAccess_mapsToEmpty() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = null,
                hasConsent = true,
                access = resolveAccess(book()).copy(options = emptyList(), canDownloadInApp = false, downloadUrl = null),
                failed = false,
            )
        assertEquals(AccessResolverState.Empty, state)
    }

    @Test
    fun publicDomainBook_withDownload_loads() {
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = null,
                hasConsent = true,
                access = resolveAccess(book()),
                failed = false,
            )
        assertTrue(state is AccessResolverState.Loaded)
        assertTrue((state as AccessResolverState.Loaded).access.canDownloadInApp)
    }

    @Test
    fun downloadOnly_countsAsLoaded() {
        val access = resolveAccess(book()).copy(options = emptyList())
        val state =
            mapAccessState(
                isOnline = true,
                consentRequiredAddonId = null,
                hasConsent = true,
                access = access,
                failed = false,
            )
        assertTrue(state is AccessResolverState.Loaded)
    }
}

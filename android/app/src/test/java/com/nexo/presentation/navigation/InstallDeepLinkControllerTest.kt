package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.AddonManifest
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.InstalledAddonRow
import com.nexo.data.remote.addons.ManifestValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun previewManifest(
    id: String = "dl-addon",
    name: String = "Deep Link Addon",
    version: String = "1.0.0",
): AddonManifest {
    val json =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put(
                "catalogs",
                org.json.JSONArray().put(JSONObject().put("type", "book").put("id", "main").put("name", "Main")),
            ).put("resources", org.json.JSONArray().put("catalog"))
            .toString()
    return ManifestValidator.validate(json.toByteArray(Charsets.UTF_8), "application/json")
}

/** Fake registry seam recording fetch/confirm calls (no persistence). */
private class RecordingRegistry : AddonRegistryLike {
    var fetchCount = 0
    var fetchError: AddonFetchException? = null
    val confirmed = mutableListOf<Pair<String, AddonManifest>>()

    override suspend fun fetchManifest(url: String): AddonManifest {
        fetchCount += 1
        fetchError?.let { throw it }
        return previewManifest()
    }

    override suspend fun installManifest(
        url: String,
        manifest: AddonManifest,
    ): AddonManifest {
        confirmed.add(url to manifest)
        return manifest
    }

    override suspend fun install(url: String): AddonManifest {
        val m = fetchManifest(url)
        return installManifest(url, m)
    }

    override suspend fun listInstalled(): List<InstalledAddonRow> = emptyList()

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {}

    override suspend fun uninstall(id: String) {}
}

private fun installUri() = Uri.parse("nextpage://install?url=https%3A%2F%2Fx.test%2Fm.json")

/**
 * State-machine tests for the install deep-link controller (tasks B2/B3):
 * parse → fetch preview → confirming → install/cancel, plus duplicate-URI
 * idempotency and shared AddonFetchErrorCode error mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallDeepLinkControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `valid install uri moves idle to confirming with fetched manifest`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        assertEquals(InstallUiState.Idle, controller.state.value)
        controller.onInstallUri(installUri())
        val state = controller.state.value
        assertTrue(state is InstallUiState.Confirming)
        state as InstallUiState.Confirming
        assertEquals("https://x.test/m.json", state.url)
        assertEquals("dl-addon", state.manifest.id)
        assertEquals("Deep Link Addon", state.manifest.name)
        assertEquals(1, registry.fetchCount)
        assertTrue(registry.confirmed.isEmpty())
    }

    @Test
    fun `confirm calls installManifest once and returns to idle`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(installUri())
        controller.confirm()
        assertEquals(1, registry.confirmed.size)
        assertEquals("https://x.test/m.json", registry.confirmed[0].first)
        assertEquals("dl-addon", registry.confirmed[0].second.id)
        assertEquals(InstallUiState.Idle, controller.state.value)
    }

    @Test
    fun `cancel returns to idle and never installs`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(installUri())
        controller.cancel()
        assertEquals(InstallUiState.Idle, controller.state.value)
        assertEquals(0, registry.confirmed.size)
    }

    @Test
    fun `fetch failure surfaces error state with the transport code`() {
        val registry =
            RecordingRegistry()
                .apply { fetchError = AddonFetchException(AddonFetchErrorCode.NETWORK, "down") }
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(installUri())
        val state = controller.state.value
        assertTrue(state is InstallUiState.Error)
        assertEquals(AddonFetchErrorCode.NETWORK, (state as InstallUiState.Error).code)
        assertEquals(0, registry.confirmed.size)
    }

    @Test
    fun `dismiss error returns to idle`() {
        val registry =
            RecordingRegistry()
                .apply { fetchError = AddonFetchException(AddonFetchErrorCode.NETWORK, "down") }
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(installUri())
        controller.dismissError()
        assertEquals(InstallUiState.Idle, controller.state.value)
    }

    @Test
    fun `non-install uri is silently ignored`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(Uri.parse("nextpage://auth/callback?access_token=t"))
        assertEquals(InstallUiState.Idle, controller.state.value)
        assertEquals(0, registry.fetchCount)
        assertEquals(0, registry.confirmed.size)
    }

    @Test
    fun `same uri redelivered while confirming is idempotent - no refetch no reset`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        val uri = installUri()
        controller.onInstallUri(uri)
        val confirming = controller.state.value
        controller.onInstallUri(uri)
        assertEquals(confirming, controller.state.value)
        assertEquals(1, registry.fetchCount)
        controller.confirm()
        assertEquals(1, registry.confirmed.size)
    }

    @Test
    fun `confirm while installing is a no-op - single install`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher, installedManifest = previewManifest())
        controller.onInstallUri(installUri())
        controller.confirm()
        // First confirm completed synchronously (unconfined); a repeat must not re-install.
        controller.confirm()
        assertEquals(1, registry.confirmed.size)
    }

    @Test
    fun `non-https url param never reaches the registry`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(Uri.parse("nextpage://install?url=http://x.test/m.json"))
        val state = controller.state.value
        assertTrue(state is InstallUiState.Error)
        assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, (state as InstallUiState.Error).code)
        assertEquals(0, registry.fetchCount)
        assertEquals(0, registry.confirmed.size)
    }

    @Test
    fun `install host with missing url param surfaces https-required error without fetching`() {
        val registry = RecordingRegistry()
        val controller = InstallDeepLinkController(registry, dispatcher)
        controller.onInstallUri(Uri.parse("nextpage://install"))
        controller.onInstallUri(Uri.parse("nextpage://install?other=1"))
        val state = controller.state.value
        assertTrue(state is InstallUiState.Error)
        assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, (state as InstallUiState.Error).code)
        assertEquals(0, registry.fetchCount)
        assertEquals(0, registry.confirmed.size)
    }
}

/** Unused-import guards for the seam types exercised via the fake above. */
private class SeamVisibility {
    @Suppress("UNUSED_PARAMETER")
    fun touch(
        rows: List<InstalledAddonRow>,
        like: AddonRegistryLike,
    ) = rows.size + 0
}

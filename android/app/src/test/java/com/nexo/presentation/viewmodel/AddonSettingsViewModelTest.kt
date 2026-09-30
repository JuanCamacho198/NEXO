package com.nexo.presentation.viewmodel

import com.nexo.data.remote.addons.AddonManifest
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.InstalledAddonRow
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddonSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val manifest =
        AddonManifest(
            id = "my-addon",
            name = "My Addon",
            version = "1.0.0",
            catalogs =
                listOf(
                    com.nexo.data.remote.addons
                        .AddonCatalogEntry("book", "main", "Main catalog"),
                ),
            resources = listOf("catalog"),
        )

    private class FakeAddonRegistry(
        private val manifest: AddonManifest,
    ) : com.nexo.data.remote.addons.AddonRegistryLike {
        var rows = mutableListOf<InstalledAddonRow>()
        val installedUrls = mutableListOf<String>()
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val uninstalled = mutableListOf<String>()
        var installError: Exception? = null

        override suspend fun listInstalled(): List<InstalledAddonRow> = rows.toList()

        override suspend fun fetchManifest(url: String): AddonManifest = manifest

        override suspend fun installManifest(
            url: String,
            manifest: AddonManifest,
        ): AddonManifest {
            installedUrls.add(url)
            rows.add(InstalledAddonRow("id-${rows.size + 1}", url, manifest, true, rows.size.toLong()))
            return manifest
        }

        override suspend fun install(url: String): AddonManifest {
            installError?.let { throw it }
            installedUrls.add(url)
            rows.add(InstalledAddonRow("id-${rows.size + 1}", url, manifest, true, rows.size.toLong()))
            return manifest
        }

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ) {
            toggles.add(id to enabled)
            rows = rows.map { if (it.id == id) it.copy(enabled = enabled) else it }.toMutableList()
        }

        override suspend fun uninstall(id: String) {
            uninstalled.add(id)
            rows = rows.filter { it.id != id }.toMutableList()
        }
    }

    @Test
    fun `refresh loads installed addons into state`() =
        runTest {
            val registry = FakeAddonRegistry(manifest)
            registry.rows.add(InstalledAddonRow("id-1", "https://example.com/m.json", manifest, true, 1))
            val vm = AddonSettingsViewModel(registry)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.installed.size)
            assertEquals(
                "My Addon",
                vm.uiState.value.installed[0]
                    .manifest.name,
            )
            assertFalse(vm.uiState.value.isBusy)
        }

    @Test
    fun `install stores by url, clears input, refreshes`() =
        runTest {
            val registry = FakeAddonRegistry(manifest)
            val vm = AddonSettingsViewModel(registry)
            vm.onUrlChange("https://example.com/m.json")
            vm.install()
            advanceUntilIdle()
            assertEquals(listOf("https://example.com/m.json"), registry.installedUrls)
            assertEquals("", vm.uiState.value.url)
            assertEquals(1, vm.uiState.value.installed.size)
            assertFalse(vm.uiState.value.isBusy)
        }

    @Test
    fun `install failure keeps url and reports via callback`() =
        runTest {
            val registry = FakeAddonRegistry(manifest)
            registry.installError =
                com.nexo.data.remote.addons.AddonFetchException(
                    com.nexo.data.remote.addons.AddonFetchErrorCode.HTTPS_REQUIRED,
                    "install URL must be https",
                )
            var reported: String? = null
            val vm = AddonSettingsViewModel(registry, onError = { reported = it })
            vm.onUrlChange("http://example.com/m.json")
            vm.install()
            advanceUntilIdle()
            assertEquals("http://example.com/m.json", vm.uiState.value.url)
            assertTrue(reported!!.contains("HTTPS_REQUIRED"))
            assertFalse(vm.uiState.value.isBusy)
        }

    @Test
    fun `install with blank url is a no-op`() =
        runTest {
            val registry = FakeAddonRegistry(manifest)
            val vm = AddonSettingsViewModel(registry)
            vm.onUrlChange("   ")
            vm.install()
            advanceUntilIdle()
            assertEquals(0, registry.installedUrls.size)
        }

    @Test
    fun `toggle and uninstall mutate the registry and refresh state`() =
        runTest {
            val registry = FakeAddonRegistry(manifest)
            registry.rows.add(InstalledAddonRow("id-1", "https://example.com/m.json", manifest, true, 1))
            val vm = AddonSettingsViewModel(registry)
            vm.refresh()
            advanceUntilIdle()
            vm.toggle("id-1", false)
            advanceUntilIdle()
            assertEquals(listOf("id-1" to false), registry.toggles)
            assertFalse(
                vm.uiState.value.installed[0]
                    .enabled,
            )
            vm.uninstall("id-1")
            advanceUntilIdle()
            assertEquals(listOf("id-1"), registry.uninstalled)
            assertEquals(0, vm.uiState.value.installed.size)
        }

    // ── MockK surface: busy transitions + captured onError ────────────────

    @Test
    fun `install wraps the registry call in busy transitions`() =
        runTest {
            val registry = mockk<AddonRegistryLike>()
            val gate = CompletableDeferred<Unit>()
            coEvery { registry.install("https://example.com/m.json") } coAnswers {
                gate.await()
                manifest
            }
            coEvery { registry.listInstalled() } returns
                listOf(
                    InstalledAddonRow("id-1", "https://example.com/m.json", manifest, true, 1),
                )
            var reported: String? = null
            val vm = AddonSettingsViewModel(registry, onError = { reported = it })

            vm.onUrlChange("https://example.com/m.json")
            vm.install()
            assertTrue("busy while the install is in flight", vm.uiState.value.isBusy)

            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isBusy)
            assertEquals("", vm.uiState.value.url)
            assertEquals(1, vm.uiState.value.installed.size)
            assertNull(reported)
        }

    @Test
    fun `install failure reports onError and preserves the url`() =
        runTest {
            val registry = mockk<AddonRegistryLike>()
            coEvery { registry.install(any()) } throws
                com.nexo.data.remote.addons.AddonFetchException(
                    com.nexo.data.remote.addons.AddonFetchErrorCode.HTTPS_REQUIRED,
                    "install URL must be https",
                )
            var reported: String? = null
            val vm = AddonSettingsViewModel(registry, onError = { reported = it })

            vm.onUrlChange("http://example.com/m.json")
            vm.install()
            advanceUntilIdle()

            assertTrue(reported!!.contains("HTTPS_REQUIRED"))
            assertEquals("http://example.com/m.json", vm.uiState.value.url)
            assertFalse(vm.uiState.value.isBusy)
        }
}

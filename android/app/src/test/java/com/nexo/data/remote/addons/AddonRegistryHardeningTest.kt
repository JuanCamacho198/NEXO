package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.addonSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge/hardening tests for the Android addon registry seam:
 * uninstall isolation at the composite level and the DEBUG-only
 * destructive-migration guard in DatabaseModule.
 */
class AddonRegistryHardeningTest {
    private fun row(
        id: String,
        name: String,
    ) = InstalledAddonRow(
        id = id,
        url = "https://$id.example/m.json",
        manifest =
            AddonManifest(
                id = name,
                name = name,
                version = "1.0.0",
                catalogs = listOf(AddonCatalogEntry("book", "main", "Main")),
                resources = listOf("catalog"),
            ),
        enabled = true,
        addedAt = 1,
    )

    private fun providers(rows: List<InstalledAddonRow>) =
        catalogProvidersWithAddons(
            builtIns =
                listOf(
                    FakeBuiltin("gutendex", CatalogSourceKind.BUILTIN),
                    FakeBuiltin("openlibrary", CatalogSourceKind.BUILTIN),
                ),
            curated = FakeBuiltin("curated", CatalogSourceKind.CURATED),
            installedAddons = rows,
        )

    private class FakeBuiltin(
        name: String,
        kind: CatalogSourceKind,
    ) : com.nexo.data.remote.catalog.CatalogProvider {
        private val source =
            com.nexo.data.remote.catalog.CatalogSourceInfo(
                "fake:$name",
                name,
                kind,
            )

        override fun listSources() = listOf(source)

        override suspend fun search(
            query: String,
            page: Int,
        ) = com.nexo.data.remote.catalog
            .PagedResult(emptyList(), null, 0)

        override suspend fun getDetails(id: String): Nothing =
            throw com.nexo.data.remote.catalog.CatalogException(
                com.nexo.data.remote.catalog.CatalogErrorCode.NOT_FOUND,
                id,
            )

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = ""
    }

    @Test
    fun `uninstall isolates - remaining composite keeps built-ins and other addons`() {
        val one = row("addon-id-1", "One")
        val two = row("addon-id-2", "Two")
        val before = providers(listOf(one, two))
        assertEquals(2, before.flatMap { it.listSources() }.count { it.kind == CatalogSourceKind.ADDON })

        val after = providers(listOf(two))
        val sources = after.flatMap { it.listSources() }
        assertEquals(2, sources.count { it.kind == CatalogSourceKind.BUILTIN })
        assertEquals(1, sources.count { it.kind == CatalogSourceKind.CURATED })
        assertTrue(sources.none { it.sourceId == addonSource(one.id) })
        assertTrue(sources.any { it.sourceId == addonSource(two.id) })
        assertFalse(sources.isEmpty())
    }

    /** fallbackToDestructiveMigration must stay guarded by BuildConfig.DEBUG (source scan). */
    @Test
    fun `DatabaseModule keeps destructive migration DEBUG-only`() {
        val candidates =
            listOf(
                java.io.File("src/main/java/com/nexo/di/modules/DatabaseModule.kt"),
                java.io.File("app/src/main/java/com/nexo/di/modules/DatabaseModule.kt"),
            )
        val source =
            candidates.firstOrNull { it.exists() }?.readText()
                ?: error("DatabaseModule.kt not found from test working dir")
        val guarded =
            Regex(
                """if \(BuildConfig\.DEBUG\)[\s\S]{0,80}fallbackToDestructiveMigration""",
            ).containsMatchIn(source)
        assertTrue("fallbackToDestructiveMigration must be inside if (BuildConfig.DEBUG)", guarded)
    }
}

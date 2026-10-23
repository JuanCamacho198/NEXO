package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import com.nexo.data.remote.addons.protocol.AddonProtocolConstants
import com.nexo.data.remote.addons.protocol.AddonShape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val V2_ROW_JSON =
    """
{
  "id": "space-books",
  "name": "Space Books",
  "version": "2.0.0",
  "catalogs": [{ "type": "book-catalog", "id": "main", "name": "Main catalog" }],
  "resources": ["search", "book-details"],
  "searchUrl": "https://space.example/search?q={query}&page={page}",
  "detailsUrl": "https://space.example/book/{bookId}",
  "resolveUrl": "https://space.example/resolve/{isbn}",
  "capabilities": ["resolve"]
}
    """.trimIndent()

private val V3_STREAM_ONLY_JSON =
    """
{
  "id": "streams-only",
  "version": "3.0.0",
  "name": "Streams Only",
  "types": ["book"],
  "catalogs": [],
  "resources": [{ "name": "stream", "types": ["book"], "idPrefixes": ["gutenberg:"] }]
}
    """.trimIndent()

private class RecordingDao : AddonDao {
    val rows = mutableListOf<AddonEntity>()

    override suspend fun getAll(): List<AddonEntity> = rows.toList()

    override suspend fun getById(id: String): AddonEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByUrl(url: String): AddonEntity? = rows.firstOrNull { it.url == url }

    override suspend fun upsert(addon: AddonEntity) {
        val index = rows.indexOfFirst { it.id == addon.id }
        if (index >= 0) rows[index] = addon else rows.add(addon)
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(enabled = enabled)
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }
}

private class SingleAnswerTransport(
    private val json: String,
) : AddonHttpTransport {
    override suspend fun fetch(url: String): AddonResource = AddonResource(200, "application/json", json.toByteArray(Charsets.UTF_8))
}

class AddonRegistryV3ReadTest {
    @Test
    fun `a stored v2 row is adapted on read and its bytes are never rewritten`() =
        runTest {
            val dao = RecordingDao()
            dao.upsert(AddonEntity(id = "0123456789abcdef", url = "https://space.example/manifest.json", manifestJson = V2_ROW_JSON, enabled = true, addedAt = 7))

            val row = AddonRegistry(dao, SingleAnswerTransport(V2_ROW_JSON)).listInstalled().single()

            assertEquals(listOf(AddonProtocolConstants.TYPE_BOOK), row.manifest.types)
            assertEquals(AddonShape.LEGACY_V2, row.manifest.nexo?.shape)
            assertEquals(
                "https://space.example/search?q={search}&page={skip}",
                row.manifest.nexo
                    ?.templates
                    ?.get("catalog"),
            )
            assertEquals(
                "https://space.example/book/{id}",
                row.manifest.nexo
                    ?.templates
                    ?.get("meta"),
            )
            assertEquals(
                "https://space.example/resolve/{isbn}",
                row.manifest.nexo
                    ?.templates
                    ?.get("stream"),
            )
            assertEquals(listOf(AddonProtocolConstants.TYPE_BOOK), row.manifest.catalogs.map { it.type })
            assertTrue(row.manifest.resources.any { it.name == "stream" })
            assertEquals(V2_ROW_JSON, dao.rows.single().manifestJson)
        }

    @Test
    fun `a v3 stream-only manifest installs and keeps the bytes it served`() =
        runTest {
            val dao = RecordingDao()
            val registry = AddonRegistry(dao, SingleAnswerTransport(V3_STREAM_ONLY_JSON))

            val installed = registry.install("https://streams.example/manifest.json")

            assertEquals("streams-only", installed.id)
            assertTrue(installed.catalogs.isEmpty())
            val row = registry.listInstalled().single()
            assertTrue(row.manifest.catalogs.isEmpty())
            assertEquals(V3_STREAM_ONLY_JSON, dao.rows.single().manifestJson)
        }

    @Test
    fun `install rejects a reserved declared type before any dao write`() =
        runTest {
            val dao = RecordingDao()
            val reserved =
                """
{
  "id": "audio-books",
  "version": "3.0.0",
  "name": "Audio",
  "types": ["audiobook"],
  "catalogs": [],
  "resources": ["catalog"]
}
                """.trimIndent()
            val registry = AddonRegistry(dao, SingleAnswerTransport(reserved))

            try {
                registry.install("https://audio.example/manifest.json")
                fail("expected UNSUPPORTED_TYPE")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.UNSUPPORTED_TYPE, err.code)
            }
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `installManifest persists the preview bytes without a second fetch`() =
        runTest {
            val dao = RecordingDao()
            val transport = SingleAnswerTransport(V3_STREAM_ONLY_JSON)
            val registry = AddonRegistry(dao, transport)
            val preview =
                AddonManifestPreview(
                    manifest = AddonManifestParserV3.loadManifestV3(V3_STREAM_ONLY_JSON.toByteArray(), "application/json"),
                    rawJson = V3_STREAM_ONLY_JSON,
                )

            registry.installManifest("https://streams.example/manifest.json", preview)

            assertEquals(1, dao.rows.size)
            assertEquals(V3_STREAM_ONLY_JSON, dao.rows.single().manifestJson)
        }
}

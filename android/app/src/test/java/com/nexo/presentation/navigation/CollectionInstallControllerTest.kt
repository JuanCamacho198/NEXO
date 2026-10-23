package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.AddonHttpTransport
import com.nexo.data.remote.addons.AddonId
import com.nexo.data.remote.addons.AddonRegistry
import com.nexo.data.remote.addons.AddonResource
import com.nexo.data.remote.addons.InMemoryAddonConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COLLECTION_URL = "https://collections.example/books.json"

private fun manifestJson(
    id: String,
    name: String = id,
    stream: Boolean = false,
): JSONObject =
    if (stream) {
        JSONObject()
            .put("id", id)
            .put("version", "3.0.0")
            .put("name", name)
            .put("types", JSONArray().put("book"))
            .put("catalogs", JSONArray())
            .put(
                "resources",
                JSONArray().put(JSONObject().put("name", "stream").put("types", JSONArray().put("book"))),
            )
    } else {
        JSONObject()
            .put("id", id)
            .put("version", "3.0.0")
            .put("name", name)
            .put("types", JSONArray().put("book"))
            .put(
                "catalogs",
                JSONArray().put(JSONObject().put("type", "book").put("id", "popular").put("name", "Popular")),
            ).put("resources", JSONArray().put("catalog").put("meta"))
    }

private fun collectionEntry(
    transportUrl: String,
    manifest: Any,
    flags: JSONObject? = null,
): JSONObject =
    JSONObject()
        .put("transportUrl", transportUrl)
        .put("transportName", "http")
        .put("manifest", manifest)
        .apply { if (flags != null) put("flags", flags) }

/** In-memory registry store: the real [AddonRegistry] drives real upsert semantics. */
private class FakeAddonDao : AddonDao {
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionInstallControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeAddonDao()
    private val consent = InMemoryAddonConsentStore()
    private lateinit var registry: AddonRegistry

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        registry =
            AddonRegistry(
                dao = dao,
                transport =
                    object : AddonHttpTransport {
                        override suspend fun fetch(url: String) = throw AddonFetchException(AddonFetchErrorCode.NETWORK, "no fetch expected")
                    },
                consent = consent,
                now = { 1_000L },
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controllerFor(body: String): CollectionInstallController =
        CollectionInstallController(
            registry = registry,
            transport =
                object : AddonHttpTransport {
                    var calls = 0

                    override suspend fun fetch(url: String): AddonResource {
                        calls++
                        return AddonResource(
                            status = 200,
                            contentType = "application/json",
                            body = body.toByteArray(Charsets.UTF_8),
                        )
                    }
                },
            mainDispatcher = dispatcher,
        )

    private fun collectionUri(url: String = COLLECTION_URL): Uri = Uri.parse("nexo://collection?url=${java.net.URLEncoder.encode(url, "UTF-8")}")

    private fun twoAddonCollection(): String =
        JSONObject()
            .put(
                "addons",
                JSONArray()
                    .put(collectionEntry("https://catalog.example/m.json", manifestJson("org.example.catalog")))
                    .put(
                        collectionEntry(
                            "https://streams.example/m.json",
                            manifestJson("org.example.streams", stream = true),
                        ),
                    ).put(
                        collectionEntry(
                            "http://plain.example/m.json",
                            manifestJson("org.example.plain"),
                        ),
                    ).put(
                        collectionEntry(
                            "https://bad.example/m.json",
                            JSONObject().put("id", "org.example.bad"),
                        ),
                    ),
            ).toString()

    @Test
    fun `valid collection link lists every importable addon and marks stream requests`() {
        val controller = controllerFor(twoAddonCollection())
        assertEquals(CollectionInstallUiState.Idle, controller.state.value)

        controller.onCollectionUri(collectionUri())

        val state = controller.state.value
        assertTrue(state is CollectionInstallUiState.Confirming)
        state as CollectionInstallUiState.Confirming
        assertEquals(COLLECTION_URL, state.collectionUrl)
        assertEquals(2, state.entries.size)
        assertEquals(2, state.skipped)
        assertEquals(
            listOf("org.example.catalog", "org.example.streams"),
            state.entries.map { it.manifest.id },
        )
        assertEquals(listOf(false, true), state.entries.map { it.requestsStream })
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `confirm installs every entry and grants no stream consent`() {
        val controller = controllerFor(twoAddonCollection())
        controller.onCollectionUri(collectionUri())
        controller.confirm()

        assertEquals(CollectionInstallUiState.Idle, controller.state.value)
        assertEquals(2, dao.rows.size)
        assertEquals(
            setOf("org.example.catalog", "org.example.streams"),
            dao.rows.map { id -> JSONObject(id.manifestJson).getString("id") }.toSet(),
        )
        // A collection cannot consent on the reader's behalf: stream stays denied
        // until the reader allows that addon individually.
        for (row in dao.rows) {
            assertFalse("consent granted for ${row.id}", registry.hasAddonConsent(row.id))
        }
        assertTrue(registry.hasAddonConsent(AddonId.fromUrl("https://catalog.example/m.json")).not())
    }

    @Test
    fun `re-import updates the manifest and preserves enabled and addedAt`() {
        val url = "https://catalog.example/m.json"
        dao.rows.add(
            AddonEntity(
                id = AddonId.fromUrl(url),
                url = url,
                manifestJson = manifestJson("org.example.catalog", name = "Old name").toString(),
                enabled = false,
                addedAt = 111L,
            ),
        )
        val controller = controllerFor(twoAddonCollection())
        controller.onCollectionUri(collectionUri())
        controller.confirm()

        val row = dao.rows.first { it.url == url }
        assertEquals(false, row.enabled)
        assertEquals(111L, row.addedAt)
        assertEquals("org.example.catalog", JSONObject(row.manifestJson).getString("id"))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `cancel installs nothing`() {
        val controller = controllerFor(twoAddonCollection())
        controller.onCollectionUri(collectionUri())
        controller.cancel()
        assertEquals(CollectionInstallUiState.Idle, controller.state.value)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `non-https collection link surfaces https-required without fetching`() {
        var fetches = 0
        val controller =
            CollectionInstallController(
                registry = registry,
                transport =
                    object : AddonHttpTransport {
                        override suspend fun fetch(url: String): AddonResource {
                            fetches++
                            return AddonResource(200, "application/json", "{}".toByteArray())
                        }
                    },
                mainDispatcher = dispatcher,
            )
        controller.onCollectionUri(collectionUri(url = "http://collections.example/books.json"))
        val state = controller.state.value
        assertTrue(state is CollectionInstallUiState.Error)
        assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, (state as CollectionInstallUiState.Error).code)
        assertEquals(0, fetches)
    }

    @Test
    fun `a collection with nothing importable surfaces an invalid-manifest error`() {
        val controller = controllerFor(JSONObject().put("addons", JSONArray()).toString())
        controller.onCollectionUri(collectionUri())
        val state = controller.state.value
        assertTrue(state is CollectionInstallUiState.Error)
        assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, (state as CollectionInstallUiState.Error).code)
    }

    @Test
    fun `transport failure surfaces the network code`() {
        val controller =
            CollectionInstallController(
                registry = registry,
                transport =
                    object : AddonHttpTransport {
                        override suspend fun fetch(url: String): AddonResource = throw AddonFetchException(AddonFetchErrorCode.NETWORK, "down")
                    },
                mainDispatcher = dispatcher,
            )
        controller.onCollectionUri(collectionUri())
        val state = controller.state.value
        assertTrue(state is CollectionInstallUiState.Error)
        assertEquals(AddonFetchErrorCode.NETWORK, (state as CollectionInstallUiState.Error).code)
    }

    @Test
    fun `same collection link redelivered while confirming is idempotent`() {
        val fetched = twoAddonCollection()
        var calls = 0
        val controller =
            CollectionInstallController(
                registry = registry,
                transport =
                    object : AddonHttpTransport {
                        override suspend fun fetch(url: String): AddonResource {
                            calls++
                            return AddonResource(200, "application/json", fetched.toByteArray())
                        }
                    },
                mainDispatcher = dispatcher,
            )
        controller.onCollectionUri(collectionUri())
        val confirming = controller.state.value
        controller.onCollectionUri(collectionUri())
        assertEquals(confirming, controller.state.value)
        assertEquals(1, calls)
    }
}

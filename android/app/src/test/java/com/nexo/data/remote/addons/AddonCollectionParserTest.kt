package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collection import rules (task 15): HTTPS-only transport urls, per-entry
 * manifest validation, a 40-entry cap, duplicate collapsing, and `flags`
 * dropped — a collection is untrusted input from a stranger.
 */
private fun catalogManifestJson(
    id: String = "org.example.a",
    name: String = "A",
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("version", "3.0.0")
        .put("name", name)
        .put("types", JSONArray().put("book"))
        .put(
            "catalogs",
            JSONArray().put(JSONObject().put("type", "book").put("id", "popular").put("name", "Popular")),
        ).put("resources", JSONArray().put("catalog").put("meta"))

private fun streamManifestJson(id: String = "org.example.streams"): JSONObject =
    JSONObject()
        .put("id", id)
        .put("version", "3.0.0")
        .put("name", "Streams")
        .put("types", JSONArray().put("book"))
        .put("catalogs", JSONArray())
        .put(
            "resources",
            JSONArray().put(JSONObject().put("name", "stream").put("types", JSONArray().put("book"))),
        )

private fun entry(
    transportUrl: String = "https://a.example/manifest.json",
    manifest: JSONObject = catalogManifestJson(),
    flags: JSONObject? = null,
): JSONObject =
    JSONObject()
        .put("transportUrl", transportUrl)
        .put("transportName", "http")
        .put("manifest", manifest)
        .apply { if (flags != null) put("flags", flags) }

private fun collection(entries: List<JSONObject>): String = JSONObject().put("addons", JSONArray(entries)).toString()

class AddonCollectionParserTest {
    @Test
    fun `parses the stremio collection shape into entries`() {
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(),
                        entry(
                            transportUrl = "https://b.example/m.json",
                            manifest = catalogManifestJson(id = "org.example.b", name = "B"),
                        ),
                    ),
                ),
            )
        assertEquals(0, parsed.skipped)
        assertEquals(2, parsed.entries.size)
        assertEquals("https://a.example/manifest.json", parsed.entries[0].transportUrl)
        assertEquals("org.example.a", parsed.entries[0].manifest.id)
        assertEquals("org.example.b", parsed.entries[1].manifest.id)
    }

    @Test
    fun `accepts a bare array of entries`() {
        val parsed = AddonCollectionParser.parse(JSONArray(listOf(entry())).toString())
        assertEquals(1, parsed.entries.size)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `input that is not a collection yields nothing and no skips`() {
        for (raw in listOf("not json", "{}", "[]", "42", "null", "{\"addons\":\"nope\"}")) {
            val parsed = AddonCollectionParser.parse(raw)
            assertTrue("expected empty for $raw", parsed.entries.isEmpty())
            assertEquals("expected no skips for $raw", 0, parsed.skipped)
        }
    }

    @Test
    fun `skips non-object entries instead of failing the collection`() {
        val addons =
            JSONArray()
                .put(JSONObject.NULL)
                .put("https://a.example/manifest.json")
                .put(JSONArray().put("https://b.example/manifest.json"))
                .put(entry())
        val parsed = AddonCollectionParser.parse(JSONObject().put("addons", addons).toString())
        assertEquals(3, parsed.skipped)
        assertEquals(listOf("https://a.example/manifest.json"), parsed.entries.map { it.transportUrl })
    }

    @Test
    fun `skips an http transport url and never downgrades it`() {
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(transportUrl = "http://plain.example/manifest.json"),
                        entry(transportUrl = "https://ok.example/m.json"),
                    ),
                ),
            )
        assertEquals(1, parsed.skipped)
        assertEquals(listOf("https://ok.example/m.json"), parsed.entries.map { it.transportUrl })
    }

    @Test
    fun `skips non-https schemes, blank urls and non-string urls`() {
        val missingUrl = JSONObject().put("manifest", catalogManifestJson())
        val numericUrl = JSONObject().put("transportUrl", 42).put("manifest", catalogManifestJson())
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(transportUrl = "file:///etc/passwd"),
                        entry(transportUrl = "ftp://example.com/m.json"),
                        entry(transportUrl = "not a url"),
                        entry(transportUrl = ""),
                        missingUrl,
                        numericUrl,
                    ),
                ),
            )
        assertEquals(6, parsed.skipped)
        assertTrue(parsed.entries.isEmpty())
    }

    @Test
    fun `skips an entry whose manifest fails v3 validation and keeps the rest`() {
        val incomplete = JSONObject().put("id", "org.example.bad").put("types", JSONArray().put("book"))
        val catalogWithoutMeta =
            catalogManifestJson(id = "org.example.noMeta").put("resources", JSONArray().put("catalog"))
        val reservedType =
            catalogManifestJson(id = "org.example.reserved").put("types", JSONArray().put("audiobook"))
        val notAnObject = JSONObject().put("transportUrl", "https://x.example/m.json").put("manifest", "nope")
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(transportUrl = "https://bad.example/m.json", manifest = incomplete),
                        entry(transportUrl = "https://no-meta.example/m.json", manifest = catalogWithoutMeta),
                        entry(transportUrl = "https://reserved.example/m.json", manifest = reservedType),
                        entry(transportUrl = "https://str.example/m.json", manifest = notAnObject),
                        entry(transportUrl = "https://ok.example/m.json"),
                    ),
                ),
            )
        assertEquals(4, parsed.skipped)
        assertEquals(listOf("https://ok.example/m.json"), parsed.entries.map { it.transportUrl })
    }

    @Test
    fun `caps at 40 entries and counts the overflow as skipped`() {
        val entries =
            (0..AddonCollectionParser.MAX_ENTRIES).map { index ->
                entry(
                    transportUrl = "https://addon-$index.example/m.json",
                    manifest = catalogManifestJson(id = "org.example.a$index"),
                )
            }
        val parsed = AddonCollectionParser.parse(collection(entries))
        assertEquals(AddonCollectionParser.MAX_ENTRIES, parsed.entries.size)
        assertEquals(1, parsed.skipped)
        assertEquals(
            "org.example.a39",
            parsed.entries
                .last()
                .manifest.id,
        )
    }

    @Test
    fun `collapses duplicate transport urls to one entry, first occurrence winning`() {
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(manifest = catalogManifestJson(id = "org.example.first")),
                        entry(manifest = catalogManifestJson(id = "org.example.second")),
                        entry(transportUrl = "https://b.example/m.json"),
                    ),
                ),
            )
        assertEquals(0, parsed.skipped)
        assertEquals(2, parsed.entries.size)
        assertEquals("org.example.first", parsed.entries[0].manifest.id)
    }

    @Test
    fun `ignores flags - a collection cannot grant an addon elevated trust`() {
        val flags =
            JSONObject()
                .put("official", true)
                .put("trusted", true)
                .put("verified", true)
                .put("consent", true)
                .put("streamConsent", true)
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(
                            transportUrl = "https://streams.example/m.json",
                            manifest = streamManifestJson().put("consent", true),
                            flags = flags,
                        ),
                    ),
                ),
            )
        assertEquals(1, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        val entry = parsed.entries[0]
        // The manifest is whatever the collection embedded, minus nothing:
        // flags never promote an entry and never add a consent field.
        assertFalse(entry.manifest.id == "official")
        assertFalse(entry.rawJson.contains("trusted"))
        assertFalse(entry.rawJson.contains("streamConsent"))
    }

    @Test
    fun `marks which entries request stream and leaves consent to the reader`() {
        val parsed =
            AddonCollectionParser.parse(
                collection(
                    listOf(
                        entry(transportUrl = "https://catalog.example/m.json"),
                        entry(
                            transportUrl = "https://streams.example/m.json",
                            manifest = streamManifestJson(),
                        ),
                    ),
                ),
            )
        assertEquals(2, parsed.entries.size)
        assertFalse(parsed.entries[0].requestsStream)
        assertTrue(parsed.entries[1].requestsStream)
    }

    @Test
    fun `keeps the embedded bytes so install persists exactly what the collection carried`() {
        val embedded = catalogManifestJson(id = "org.example.embedded")
        val parsed =
            AddonCollectionParser.parse(
                collection(listOf(entry(transportUrl = "https://a.example/m.json", manifest = embedded))),
            )
        val stored = parsed.entries[0].rawJson
        assertEquals("org.example.embedded", JSONObject(stored).getString("id"))
        assertEquals("3.0.0", JSONObject(stored).getString("version"))
        assertEquals(
            "org.example.embedded",
            AddonManifestParserV3.loadManifestV3(stored.toByteArray(), "application/json").id,
        )
    }
}

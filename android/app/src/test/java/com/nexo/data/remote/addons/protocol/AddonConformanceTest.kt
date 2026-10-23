package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shared conformance corpus, read from the same JSON the TypeScript package
 * reads (`packages/addon-protocol/test/fixtures/conformance/`).
 *
 * The corpus — not this file — holds every expectation. A Gradle `Sync` task
 * copies it into a generated unit-test resource root, so the two platforms
 * cannot drift without one of them failing here.
 */
class AddonConformanceTest {
    private val corpusRootName = "conformance"
    private val expectedFile = "expected.json"

    // ------------------------------------------------------------------ corpus

    private fun corpusRoot(): File {
        val url =
            javaClass.classLoader?.getResource(corpusRootName)
                ?: throw AssertionError(
                    "$corpusRootName corpus is not on the unit-test classpath: " +
                        "the conformance Sync task did not run or the generated directory is not a test resource root",
                )
        return File(url.toURI())
    }

    private fun readText(relativePath: String): String {
        val file = File(corpusRoot(), relativePath)
        if (!file.isFile) throw AssertionError("conformance corpus is unreadable: $relativePath")
        return file.readText()
    }

    private fun readBytes(relativePath: String): ByteArray {
        val file = File(corpusRoot(), relativePath)
        if (!file.isFile) throw AssertionError("conformance corpus is unreadable: $relativePath")
        return file.readBytes()
    }

    private fun listJsonFiles(directory: String): List<String> {
        val dir = File(corpusRoot(), directory)
        if (!dir.isDirectory) throw AssertionError("conformance corpus is missing directory: $directory")
        return dir
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != expectedFile }
            ?.map { it.name }
            ?.sorted()
            ?: throw AssertionError("conformance corpus is unreadable: $directory")
    }

    private fun readCorpusJson(relativePath: String): JsonObject = Json.parseToJsonElement(readText(relativePath)).jsonObject

    // ----------------------------------------------------------------- verdicts

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun number(value: Int): JsonElement = JsonPrimitive(value)

    private fun flag(value: Boolean): JsonElement = JsonPrimitive(value)

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun errorVerdict(code: AddonFetchErrorCode): JsonObject =
        buildJsonObject {
            put("ok", flag(false))
            put("error", JsonPrimitive(code.wireCode))
        }

    private fun describeManifest(manifest: AddonManifestV3): JsonObject {
        val catalogExtras =
            buildJsonObject {
                for (catalog in manifest.catalogs) {
                    put(catalog.id, stringArray(catalog.extra?.map { it.name } ?: emptyList()))
                }
            }
        val resourceTypes =
            buildJsonObject {
                for (resource in manifest.resources) {
                    val types = resource.types ?: continue
                    put(resource.name, stringArray(types))
                }
            }
        val resourceIdPrefixes =
            buildJsonObject {
                for (resource in manifest.resources) {
                    val idPrefixes = resource.idPrefixes ?: continue
                    put(resource.name, stringArray(idPrefixes))
                }
            }
        val templates =
            manifest.nexo
                ?.templates
                ?.takeIf { it.isNotEmpty() }
                ?.let { declared ->
                    buildJsonObject { for ((resource, template) in declared) put(resource, JsonPrimitive(template)) }
                }

        return buildJsonObject {
            put("ok", flag(true))
            put("shape", text(manifest.nexo?.shape?.wire))
            put("types", stringArray(manifest.types))
            put("catalogIds", stringArray(manifest.catalogs.map { it.id }))
            put("catalogExtras", catalogExtras)
            put("resourceNames", stringArray(manifest.resources.map { it.name }))
            put("resourceTypes", resourceTypes)
            put("resourceIdPrefixes", resourceIdPrefixes)
            put("idPrefixes", manifest.idPrefixes?.let { stringArray(it) } ?: JsonNull)
            put("templates", templates ?: JsonNull)
        }
    }

    private fun shapeOf(
        expected: JsonObject,
        fixture: String,
    ): AddonShape {
        val wire =
            expected["shape"]?.jsonPrimitive?.contentOrNull
                ?: throw AssertionError("$fixture: expected verdict has no shape")
        return AddonShape.fromWire(wire) ?: throw AssertionError("$fixture: unknown shape \"$wire\"")
    }

    /**
     * Mirrors `describePayload` in `conformance.test.ts`: the expected verdict
     * chooses the parser and which ids are reported, so the corpus stays the
     * only source of truth.
     */
    private fun describePayload(
        body: Any?,
        expected: JsonObject,
        fixture: String,
    ): JsonObject {
        val kind =
            expected["kind"]?.jsonPrimitive?.contentOrNull
                ?: throw AssertionError("$fixture: expected verdict has no kind")

        if (kind == "catalog") {
            val shape = shapeOf(expected, fixture)
            val parsed = AddonPayloads.parseCatalogPayload(body, shape)
            val expectedAuthors = expected["authorsById"]?.jsonObject ?: JsonObject(emptyMap())
            val authorsById =
                buildJsonObject {
                    for (meta in parsed.metas) {
                        if (expectedAuthors.containsKey(meta.id)) put(meta.id, stringArray(meta.authors))
                    }
                }
            return buildJsonObject {
                put("kind", JsonPrimitive("catalog"))
                put("shape", JsonPrimitive(shape.wire))
                put("metaCount", number(parsed.metas.size))
                put("firstMetaId", text(parsed.metas.firstOrNull()?.id))
                put("lastMetaId", text(parsed.metas.lastOrNull()?.id))
                put("authorsById", authorsById)
                put("skipped", number(parsed.skipped))
            }
        }

        if (kind == "meta") {
            val shape = shapeOf(expected, fixture)
            val parsed = AddonPayloads.parseMetaPayload(body, shape)
            return buildJsonObject {
                put("kind", JsonPrimitive("meta"))
                put("shape", JsonPrimitive(shape.wire))
                put("metaId", text(parsed?.id))
                put("isbn13", text(parsed?.isbn13))
            }
        }

        if (kind == "streams") {
            val parsed = AddonPayloads.parseStreamPayload(body)
            val streams =
                buildJsonArray {
                    for (stream in parsed.streams) {
                        add(
                            buildJsonObject {
                                put("url", JsonPrimitive(stream.url))
                                put("source", JsonPrimitive(stream.source.wire))
                                put("format", JsonPrimitive(stream.format.wire))
                                put("accessType", JsonPrimitive(stream.accessType.wire))
                            },
                        )
                    }
                }
            return buildJsonObject {
                put("kind", JsonPrimitive("streams"))
                put("streams", streams)
                put("skipped", number(parsed.skipped))
            }
        }

        throw AssertionError("$fixture: unknown payload verdict kind \"$kind\"")
    }

    /** Top-level key diff, so a divergence names the field instead of dumping JSON. */
    private fun diffVerdict(
        fixture: String,
        actual: JsonObject,
        expected: JsonObject,
    ): List<String> =
        (actual.keys + expected.keys)
            .sorted()
            .filter { actual[it] != expected[it] }
            .map { "$fixture: $it: expected ${expected[it]}, got ${actual[it]}" }

    private fun assertNoDiffs(diffs: List<String>) {
        assertTrue(diffs.joinToString("\n"), diffs.isEmpty())
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun `manifests every fixture parses to its expected verdict`() {
        val expectation = readCorpusJson("manifests/$expectedFile")
        val contentType = expectation.getValue("contentType").jsonPrimitive.content
        val cases = expectation.getValue("cases").jsonObject
        val diffs = mutableListOf<String>()

        for (file in listJsonFiles("manifests")) {
            val expected = cases[file]
            if (expected == null) {
                diffs += "$file: no expected verdict in manifests/$expectedFile"
                continue
            }

            val actual =
                try {
                    describeManifest(AddonManifestParserV3.loadManifestV3(readBytes("manifests/$file"), contentType))
                } catch (error: AddonFetchException) {
                    errorVerdict(error.code)
                } catch (error: Exception) {
                    diffs += "$file: threw a non-AddonFetchException: $error"
                    null
                }
            if (actual == null) continue

            diffs += diffVerdict(file, actual, expected.jsonObject)
        }

        assertNoDiffs(diffs)
    }

    @Test
    fun `payloads every fixture parses to its expected verdict`() {
        val expectation = readCorpusJson("payloads/$expectedFile")
        val cases = expectation.getValue("cases").jsonObject
        val diffs = mutableListOf<String>()

        for (file in listJsonFiles("payloads")) {
            val expected = cases[file]
            if (expected == null) {
                diffs += "$file: no expected verdict in payloads/$expectedFile"
                continue
            }

            val expectedVerdict = expected.jsonObject
            val body = JSONTokener(readText("payloads/$file")).nextValue()
            val actual =
                try {
                    describePayload(body, expectedVerdict, file)
                } catch (error: AssertionError) {
                    throw error
                } catch (error: Exception) {
                    diffs += "$file: parser threw instead of returning an empty result: $error"
                    null
                }
            if (actual == null) continue

            diffs += diffVerdict(file, actual, expectedVerdict)
        }

        assertNoDiffs(diffs)
    }

    @Test
    fun `request URLs every case builds its expected URL`() {
        val corpus = readCorpusJson("urls.json")
        val contentType = corpus.getValue("contentType").jsonPrimitive.content
        val manifests = corpus.getValue("manifests").jsonObject
        val diffs = mutableListOf<String>()

        for (element in corpus.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val caseName = case["name"]?.jsonPrimitive?.contentOrNull ?: "(unnamed case)"
            val manifestName = case.getValue("manifest").jsonPrimitive.content
            val source = manifests[manifestName]
            if (source == null) {
                diffs += "$caseName: unknown manifest \"$manifestName\""
                continue
            }

            val manifest =
                try {
                    AddonManifestParserV3.parseManifestV3(source.toString().toByteArray(Charsets.UTF_8), contentType)
                } catch (error: AddonFetchException) {
                    diffs += "$caseName: manifest \"$manifestName\" failed to parse: ${error.code.wireCode}"
                    continue
                }

            val addon =
                AddonDescriptorV3(
                    id = manifest.id,
                    installUrl = case.getValue("installUrl").jsonPrimitive.content,
                    addedAt = 0L,
                    enabled = true,
                    manifest = manifest,
                )
            val request = case.getValue("request").jsonObject
            val req =
                ResourceRequest(
                    resource = request.getValue("resource").jsonPrimitive.content,
                    type = request.getValue("type").jsonPrimitive.content,
                    id = request.getValue("id").jsonPrimitive.content,
                    extra = request["extra"]?.jsonObject?.let { readExtra(it) },
                )

            val expectedError = case["expectedError"]?.jsonPrimitive?.contentOrNull
            if (expectedError != null) {
                try {
                    val url = AddonTemplates.buildRequestUrl(addon, req)
                    diffs += "$caseName: expected $expectedError, built $url"
                } catch (error: AddonFetchException) {
                    if (error.code.wireCode != expectedError) {
                        diffs += "$caseName: expected $expectedError, got ${error.code.wireCode}"
                    }
                } catch (error: Exception) {
                    diffs += "$caseName: threw a non-AddonFetchException: $error"
                }
                continue
            }

            val expectedUrl = case["expectedUrl"]?.jsonPrimitive?.contentOrNull
            try {
                val url = AddonTemplates.buildRequestUrl(addon, req)
                if (url != expectedUrl) diffs += "$caseName: expected $expectedUrl, got $url"
            } catch (error: Exception) {
                diffs += "$caseName: threw $error"
            }
        }

        assertNoDiffs(diffs)
    }

    /**
     * The corpus JSON is read with kotlinx so `extra` keeps its written order:
     * `encodeExtra` serializes in insertion order and the expected URL depends
     * on it. `org.json` backs extra with a `HashMap` and would reorder it.
     */
    private fun readExtra(extra: JsonObject): Map<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, value) in extra) values[key] = primitiveValue(value)
        return values
    }

    private fun primitiveValue(value: JsonElement): Any? =
        when (value) {
            is JsonNull -> null
            is JsonPrimitive ->
                if (value.isString) {
                    value.content
                } else {
                    value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
                }
            else -> value.toString()
        }
}

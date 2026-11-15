package com.nexo.data.repository

import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationHttpResponse
import com.nexo.data.remote.translation.TranslationHttpTransport
import com.nexo.data.remote.wiktionary.WiktionaryClient
import com.nexo.data.remote.wiktionary.WiktionaryHttpResponse
import com.nexo.data.remote.wiktionary.WiktionaryHttpTransport
import com.nexo.domain.definition.DefinitionErrorCode
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WordLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository wires the cascade to the two real clients. The fakes are at the
 * HTTP boundary (where the clients already end), so this proves the WIRING —
 * which source each wiki is asked for, and that step 3 goes through the existing
 * MyMemory client — without touching the network.
 */
class RemoteDefinitionRepositoryTest {
    private class WikiTransport(
        private val byUrl: (String) -> WiktionaryHttpResponse,
    ) : WiktionaryHttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String): WiktionaryHttpResponse {
            urls += url
            return byUrl(url)
        }
    }

    private class MyMemoryTransport(
        private val translatedText: String?,
    ) : TranslationHttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String): TranslationHttpResponse {
            urls += url
            if (translatedText == null) return TranslationHttpResponse(503, "down")
            val body =
                """{"responseData":{"translatedText":${quote(translatedText)},"match":0.9},"responseStatus":200}"""
            return TranslationHttpResponse(200, body)
        }
    }

    private fun parseEnvelope(wikitext: String): String = """{"parse":{"title":"w","wikitext":{"*":${quote(wikitext)}}}}"""

    private fun repo(
        wiki: WiktionaryHttpTransport,
        translator: TranslationHttpTransport? = null,
    ) = RemoteDefinitionRepository(
        wiktionaryClient = WiktionaryClient(wiki),
        translationClient =
            MyMemoryTranslationClient(
                translator ?: MyMemoryTransport(translatedText = null),
            ),
    )

    private fun householdEs() =
        """
        == {{lengua|en}} ==
        ==== {{sustantivo|en}} ====
        ;1: {{plm|casa}}.
        """.trimIndent()

    private fun juxtapositionEn() =
        """
        ==English==
        ===Noun===
        # The [[nearness]] of objects with little or no [[delimiter]].
        """.trimIndent()

    @Test
    fun `native hit asks only the reader-language wiki`() =
        runTest {
            val wiki =
                WikiTransport { url ->
                    if (url.contains("//es.wiktionary.org")) {
                        WiktionaryHttpResponse(200, parseEnvelope(householdEs()))
                    } else {
                        error("the English wiki must not be asked when step 1 hits: $url")
                    }
                }
            val memory = MyMemoryTransport(translatedText = "unused")
            val result =
                repo(wiki, memory).define(
                    DefinitionRequest(
                        word = "house",
                        readerLanguage = "es",
                        wordLanguage = WordLanguage.known("en"),
                    ),
                )

            assertEquals(DefinitionSource.Native, result.source)
            assertEquals(1, wiki.urls.size)
            assertTrue(memory.urls.isEmpty())
        }

    @Test
    fun `step 3 translates through the existing MyMemory client`() =
        runTest {
            val wiki =
                WikiTransport { url ->
                    if (url.contains("//es.wiktionary.org")) {
                        WiktionaryHttpResponse(200, """{"error":{"code":"missingtitle"}}""")
                    } else {
                        WiktionaryHttpResponse(200, parseEnvelope(juxtapositionEn()))
                    }
                }
            val memory = MyMemoryTransport(translatedText = "La cercanía de objetos")
            val result =
                repo(wiki, memory).define(
                    DefinitionRequest(
                        word = "juxtaposition",
                        readerLanguage = "es",
                        wordLanguage = WordLanguage.known("en"),
                    ),
                )

            assertEquals(DefinitionSource.Translated, result.source)
            assertTrue(result.definitions.any { it.text.contains("cercanía") })
            assertEquals(1, memory.urls.size)
            assertTrue("MyMemory must be asked es as the target", memory.urls.single().contains("langpair=en%7Ces"))
        }

    @Test
    fun `every wiki down surfaces a failure and never a silent empty`() =
        runTest {
            val wiki = WikiTransport { error("network down") }
            val result =
                repo(wiki).define(
                    DefinitionRequest(
                        word = "house",
                        readerLanguage = "es",
                        wordLanguage = WordLanguage.known("en"),
                    ),
                )

            assertTrue(result.definitions.isEmpty())
            assertNotNull(result.failure)
            assertEquals(DefinitionErrorCode.NETWORK, result.failure?.code)
        }

    @Test
    fun `a word absent everywhere is empty and not a failure`() =
        runTest {
            val wiki =
                WikiTransport { WiktionaryHttpResponse(200, """{"error":{"code":"missingtitle"}}""") }
            val result =
                repo(wiki).define(
                    DefinitionRequest(
                        word = "qqqqzzzz",
                        readerLanguage = "es",
                        wordLanguage = WordLanguage.known("en"),
                    ),
                )

            assertTrue(result.definitions.isEmpty())
            assertNull(result.failure)
            assertTrue(result.isEmpty)
            assertTrue(result.attribution.contains("CC BY-SA"))
        }

    private companion object {
        fun quote(s: String): String =
            kotlinx.serialization.json.Json
                .encodeToString(kotlinx.serialization.serializer<String>(), s)
    }
}

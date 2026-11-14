package com.nexo.data.repository

import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WIKTIONARY_ATTRIBUTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefinitionCacheCodecTest {
    private fun result(
        definitions: List<DefinitionItem> = listOf(DefinitionItem("casa", "sustantivo")),
        source: DefinitionSource? = DefinitionSource.Native,
    ) = DefinitionResult(
        word = "house",
        definitions = definitions,
        source = source,
        wordLanguage = "en",
        readerLanguage = "es",
        wordLanguageKnown = false,
    )

    @Test
    fun `a real result round-trips every field`() {
        val encoded = DefinitionCacheCodec.encode(result())
        assertNotNull(encoded)
        val decoded = DefinitionCacheCodec.decode(encoded!!)
        assertEquals("house", decoded?.word)
        assertEquals(listOf(DefinitionItem("casa", "sustantivo")), decoded?.definitions)
        assertEquals(DefinitionSource.Native, decoded?.source)
        assertEquals("en", decoded?.wordLanguage)
        assertEquals("es", decoded?.readerLanguage)
        assertEquals(false, decoded?.wordLanguageKnown)
    }

    @Test
    fun `the attribution is re-attached on decode`() {
        val decoded = DefinitionCacheCodec.decode(DefinitionCacheCodec.encode(result())!!)
        assertEquals(WIKTIONARY_ATTRIBUTION, decoded?.attribution)
    }

    @Test
    fun `an empty result is never encoded`() {
        assertNull(DefinitionCacheCodec.encode(result(definitions = emptyList(), source = null)))
    }

    @Test
    fun `a corrupt payload decodes to null instead of throwing`() {
        assertNull(DefinitionCacheCodec.decode("{ not json"))
    }

    @Test
    fun `an unknown source decodes to null`() {
        val payload = """{"word":"house","definitions":[],"source":"WAT","word_language":"en","reader_language":"es","word_language_known":true}"""
        assertNull(DefinitionCacheCodec.decode(payload))
    }

    @Test
    fun `a five-hundred-item payload stays within the size bound`() {
        val many = (1..500).map { DefinitionItem("definición número $it", "sustantivo") }
        val encoded = DefinitionCacheCodec.encode(result(definitions = many))
        assertNotNull(encoded)
        // The ODD bounds the serialised payload at 64 KB.
        assert(encoded!!.length < 64 * 1024) { "payload was ${encoded.length} bytes" }
    }
}

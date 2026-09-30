package com.nexo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DictionaryNormalizerTest {
    /** The shared conformance table (Rust `NORMALIZATION_VECTORS`, TS `dictionaryKey.test.ts`). */
    private val sharedVectors =
        listOf(
            "" to "",
            "   " to "",
            "  Serendipity  " to "serendipity",
            "café" to "cafe",
            "CAFÉ" to "cafe",
            "  Café  " to "cafe",
            "Ñandú" to "nandu",
            "Ōkami" to "okami",
            "Řeka" to "reka",
            "Île" to "ile",
            "Āris" to "aris",
            "Ștefan" to "stefan",
            "İstanbul" to "istanbul",
            "Abyss," to "abyss,",
            "(Ephemeral)" to "(ephemeral)",
            "¡Hola!" to "¡hola!",
            "Ephemeral's" to "ephemeral's",
        )

    @Test
    fun `shared conformance vectors normalize identically`() {
        sharedVectors.forEach { (input, expected) ->
            assertEquals("normalize(\"$input\")", expected, DictionaryNormalizer.normalize(input))
        }
    }

    @Test
    fun `stroked letters without a canonical decomposition stay unchanged`() {
        assertEquals("łodz", DictionaryNormalizer.normalize("Łódź"))
        assertEquals("øre", DictionaryNormalizer.normalize("Øre"))
        assertEquals("đak", DictionaryNormalizer.normalize("Đak"))
    }

    @Test
    fun `lowercasing is locale-independent`() {
        val previous = Locale.getDefault()
        try {
            // Turkish dotted/dotless I is the case that a locale-sensitive lowercase gets wrong.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "istanbul",
                DictionaryNormalizer.normalize(sharedVectors.first { it.first == "İstanbul" }.first),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}

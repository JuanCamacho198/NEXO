package com.nexo.domain.definition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser proven against the REAL downloaded wikitext fixtures.
 *
 * The fixtures under `src/test/resources/wiktionary/fixtures/` are verbatim
 * responses from `{lang}.wiktionary.org/w/api.php?action=parse&prop=wikitext`.
 * They are the parser's contract: a rule that the real pages do not exercise is
 * not a rule the parser needs.
 *
 * No test in this file touches the network.
 */
class WiktionaryParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The fixture is the raw API envelope; the parser takes `parse.wikitext["*"]`.
     * Pulling it here proves the envelope the client targets is the one the real
     * download produced.
     */
    private fun wikitext(name: String): String {
        val raw =
            javaClass.classLoader
                ?.getResourceAsStream("wiktionary/fixtures/$name")
                ?.bufferedReader()
                ?.readText()
                ?: error("missing test fixture wiktionary/fixtures/$name")
        val root = json.parseToJsonElement(raw)
        val parse = root.jsonObject["parse"] ?: error("fixture $name has no parse node")
        val node = parse.jsonObject["wikitext"] ?: error("fixture $name has no wikitext")
        return node.jsonObject["*"]?.jsonPrimitive?.content
            ?: error("fixture $name has no wikitext[*]")
    }

    // ── The fixtures themselves ───────────────────────────────────────────

    /**
     * Guards the fixtures: if a download had returned an error page, every
     * assertion below would pass vacuously on empty input.
     */
    @Test
    fun fixtures_carry_the_real_wikitext_payload() {
        val house = wikitext("es_house.json")
        assertTrue("house must be an English entry", house.contains("{{lengua|en}}"))

        val casa = wikitext("es_casa.json")
        assertTrue("casa must carry a Spanish section", casa.contains("{{lengua|es}}"))
        assertTrue("casa must carry an Italian section", casa.contains("{{lengua|it}}"))
        assertTrue(
            "casa is the multi-language case",
            casa.split("== {{lengua|").size - 1 >= 18,
        )

        val juxtaposition = wikitext("en_juxtaposition.json")
        assertTrue("juxtaposition is an en-wiktionary page", juxtaposition.contains("==English=="))
    }

    // ── The es shape ──────────────────────────────────────────────────────

    @Test
    fun house_in_english_section_gives_spanish_gloss() {
        val defs = WiktionaryParser.definitions(wikitext("es_house.json"), "en", "es")
        val joined = defs.joinToString(" | ") { it.text }
        assertTrue("expected 'casa' in $joined", joined.contains("casa"))
        assertTrue("expected 'sala' in $joined", joined.contains("sala"))
    }

    @Test
    fun house_reports_part_of_speech() {
        val defs = WiktionaryParser.definitions(wikitext("es_house.json"), "en", "es")
        val parts = defs.mapNotNull { it.partOfSpeech }
        assertTrue("expected a sustantivo sense, got $parts", parts.any { it.contains("sustantivo") })
    }

    @Test
    fun phrasal_verb_give_up_gives_spanish_gloss() {
        val defs = WiktionaryParser.definitions(wikitext("es_give_up.json"), "en", "es")
        val joined = defs.joinToString(" | ") { it.text }
        assertTrue("expected 'rendirse' in $joined", joined.contains("rendirse"))
        assertTrue("expected 'desistir' in $joined", joined.contains("desistir"))
    }

    @Test
    fun serendipity_gives_spanish_gloss() {
        val defs = WiktionaryParser.definitions(wikitext("es_serendipity.json"), "en", "es")
        val joined = defs.joinToString(" | ") { it.text }
        assertTrue("expected 'serendipia' in $joined", joined.contains("serendipia"))
    }

    /**
     * The hard case: `casa` stacks 20 language sections on one page. Asking for
     * the SPANISH section must return the Spanish sense (a dwelling), and asking
     * for the ITALIAN one must return the Italian sense — the only proof that
     * language selection actually selects.
     */
    @Test
    fun casa_selects_spanish_and_italian_apart() {
        val wikitext = wikitext("es_casa.json")
        val es = WiktionaryParser.definitions(wikitext, "es", "es").joinToString(" | ") { it.text }
        val it = WiktionaryParser.definitions(wikitext, "it", "es").joinToString(" | ") { it.text }

        assertTrue(
            "Spanish section must define casa as a building, got $es",
            es.contains("edificación") || es.contains("edificacion"),
        )
        assertTrue(
            "Spanish section must carry the dwelling senses, got $es",
            es.contains("domicilio") || es.contains("vivienda"),
        )
        assertTrue("Italian section must define casa as tienda, got $it", it.contains("tienda"))
        assertEquals("language selection must produce different output", false, es == it)
    }

    /** A language the page does not carry is not an error: empty, not a throw. */
    @Test
    fun missing_language_section_is_empty_not_error() {
        assertTrue(WiktionaryParser.definitions(wikitext("es_house.json"), "ja", "es").isEmpty())
    }

    // ── The en shape ──────────────────────────────────────────────────────

    @Test
    fun en_juxtaposition_yields_english_definitions() {
        val defs = WiktionaryParser.definitions(wikitext("en_juxtaposition.json"), "en", "en")
        val joined = defs.joinToString(" | ") { it.text }
        assertTrue("en shape must produce senses", defs.isNotEmpty())
        assertTrue(
            "expected the real Noun senses, got $joined",
            joined.contains("nearness") || joined.contains("emphasis"),
        )
        assertTrue(
            "expected the Verb sense, got $joined",
            joined.contains("place in juxtaposition"),
        )
    }

    /**
     * The decoys: `#*` quotations, `#:` examples, `##` sub-senses and the
     * Translations table must NOT become definitions. A parser that took them
     * would return a quotation as the meaning of the word.
     */
    @Test
    fun en_parser_rejects_quotations_examples_and_subsenses() {
        val defs = WiktionaryParser.definitions(wikitext("en_juxtaposition.json"), "en", "en")
        val joined = defs.joinToString(" | ") { it.text }
        assertTrue("a quotation leaked: $joined", !joined.contains("Coleridge"))
        assertTrue("an example leaked: $joined", !joined.contains("mother father"))
        assertTrue("a ## sub-sense leaked: $joined", !joined.contains("absence of operators"))
        assertTrue("translations leaked: $joined", !joined.contains("justaposição"))
    }

    /** The en block cutter selects the language, as the es one does. */
    @Test
    fun en_block_cutter_selects_the_requested_language() {
        val wikitext = wikitext("en_juxtaposition.json")
        assertTrue(
            "absent language must not fall back",
            WiktionaryParser.definitions(wikitext, "ja", "ja").isEmpty(),
        )
        assertTrue(
            "a dropped cross-reference must not borrow the English block",
            WiktionaryParser.definitions(wikitext, "fr", "fr").isEmpty(),
        )
    }

    // ── Totality ──────────────────────────────────────────────────────────

    @Test
    fun garbage_and_empty_input_do_not_throw() {
        assertTrue(WiktionaryParser.definitions("", "en", "es").isEmpty())
        assertTrue(WiktionaryParser.definitions("   \n\n  ", "en", "es").isEmpty())
        assertTrue(WiktionaryParser.definitions("not wikitext at all {{{", "en", "es").isEmpty())
        assertTrue(
            WiktionaryParser
                .definitions("== {{lengua|en}} ==\n=== Title ===\nno senses here", "en", "es")
                .isEmpty(),
        )
    }
}

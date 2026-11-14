package com.nexo.domain.definition

import com.nexo.data.remote.wiktionary.WiktionaryErrorCode
import com.nexo.data.remote.wiktionary.WiktionaryFetch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cascade's ordering and its two distinct failure outcomes, proven with
 * recorded fakes against the REAL fixtures.
 *
 * No test here touches the network.
 */
class DefinitionCascadeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun wikitext(name: String): String {
        val raw =
            javaClass.classLoader
                ?.getResourceAsStream("wiktionary/fixtures/$name")
                ?.bufferedReader()
                ?.readText()
                ?: error("missing test fixture wiktionary/fixtures/$name")
        val parse = json.parseToJsonElement(raw).jsonObject["parse"] ?: error("no parse node")
        return parse.jsonObject["wikitext"]
            ?.jsonObject
            ?.get("*")
            ?.jsonPrimitive
            ?.content
            ?: error("no wikitext[*]")
    }

    /** Records which sources were asked, so "step 1 hit" is assertable. */
    private class Recorder {
        val native = mutableListOf<String>()
        val english = mutableListOf<String>()
        val translated = mutableListOf<Pair<String, String>>()
    }

    private fun cascade(
        recorder: Recorder,
        native: suspend (String, String) -> WiktionaryFetch,
        english: suspend (String, String) -> WiktionaryFetch,
        translate: suspend (String, String) -> String? = { _, _ -> null },
    ) = DefinitionCascade(
        nativeSource =
            WikitextSource { word, wiki ->
                recorder.native += word
                native(word, wiki)
            },
        englishSource =
            WikitextSource { word, wiki ->
                recorder.english += word
                english(word, wiki)
            },
        translator =
            GlossTranslator { text, target ->
                recorder.translated += text to target
                translate(text, target)
            },
    )

    private fun request(
        word: String,
        readerLanguage: String = "es",
        wordLanguage: WordLanguage = WordLanguage.known("en"),
    ) = DefinitionRequest(word = word, readerLanguage = readerLanguage, wordLanguage = wordLanguage)

    // ── Step 1 ────────────────────────────────────────────────────────────

    @Test
    fun step_one_hit_is_native_and_never_calls_the_fallbacks() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.Success(wikitext("es_house.json")) },
                    english = { _, _ -> error("step 2 must not run when step 1 hits") },
                ).define(request("house"))

            assertEquals(DefinitionSource.Native, result.source)
            assertTrue(result.definitions.isNotEmpty())
            assertEquals(listOf("house"), recorder.native)
            assertTrue(recorder.english.isEmpty())
            assertTrue(recorder.translated.isEmpty())
            assertTrue(result.attribution.contains("CC BY-SA"))
        }

    @Test
    fun casa_as_a_spanish_word_is_native_and_does_not_touch_en() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.Success(wikitext("es_casa.json")) },
                    english = { _, _ -> error("en.wiktionary must not run for a native hit") },
                ).define(request("casa", readerLanguage = "es", wordLanguage = WordLanguage.known("es")))

            assertEquals(DefinitionSource.Native, result.source)
            val joined = result.definitions.joinToString(" | ") { it.text }
            assertTrue("expected the real Spanish senses, got $joined", joined.contains("edificación") || joined.contains("edificacion"))
            assertTrue(recorder.english.isEmpty())
        }

    /**
     * The case that justifies the whole cascade: `juxtaposition` has no
     * es-wiktionary entry (step 1 misses) but a full en.wiktionary one (step 2
     * hits), which the translator passes into Spanish (step 3).
     */
    @Test
    fun juxtaposition_walks_all_three_steps_and_marks_translated() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.MissingPage },
                    english = { _, _ -> WiktionaryFetch.Success(wikitext("en_juxtaposition.json")) },
                    translate = { text, _ -> "ES($text)" },
                ).define(request("juxtaposition"))

            assertEquals(listOf("juxtaposition"), recorder.native)
            assertEquals(listOf("juxtaposition"), recorder.english)
            assertTrue("step 3 must run", recorder.translated.isNotEmpty())
            assertTrue(recorder.translated.all { it.second == "es" })
            assertEquals(DefinitionSource.Translated, result.source)
            val joined = result.definitions.joinToString(" | ") { it.text }
            assertTrue("expected translated glosses, got $joined", joined.contains("ES("))
            assertTrue("the English sense must have been translated", joined.contains("nearness"))
        }

    @Test
    fun a_page_without_the_word_section_falls_through_to_english() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.Success(wikitext("es_casa.json")) },
                    english = { _, _ -> WiktionaryFetch.Success(wikitext("en_juxtaposition.json")) },
                    translate = { text, _ -> "ES($text)" },
                ).define(request("juxtaposition"))

            assertEquals(DefinitionSource.Translated, result.source)
            assertTrue(result.definitions.isNotEmpty())
        }

    // ── The two outcomes that must not be conflated ───────────────────────

    /** Nothing anywhere: empty (honest "no definition"), not a failure. */
    @Test
    fun nothing_found_is_an_empty_result_not_a_failure() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.MissingPage },
                    english = { _, _ -> WiktionaryFetch.MissingPage },
                ).define(request("qqqqzzzz"))

            assertTrue(result.definitions.isEmpty())
            assertNull(result.source)
            assertTrue("an empty result is not a failure", result.isEmpty)
            assertNull(result.failure)
            // The licence still travels, even with no text to attribute.
            assertTrue(result.attribution.contains("CC BY-SA"))
            assertEquals("qqqqzzzz", result.word)
        }

    /**
     * Every source FAILED to answer: that is a service failure, and it must NOT
     * be presented as a "no definition" (ODD invariant 2).
     */
    @Test
    fun every_source_failing_is_a_failure_not_an_empty_result() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ ->
                        WiktionaryFetch.Failure(WiktionaryErrorCode.NETWORK, "es wiki down")
                    },
                    english = { _, _ ->
                        WiktionaryFetch.Failure(WiktionaryErrorCode.NETWORK, "en wiki down")
                    },
                ).define(request("house"))

            assertTrue(result.definitions.isEmpty())
            assertFalse("a service failure must not look empty", result.isEmpty)
            assertNotNull(result.failure)
            assertEquals(DefinitionErrorCode.NETWORK, result.failure?.code)
            // The licence is still carried: the obligation is unconditional.
            assertTrue(result.attribution.contains("CC BY-SA"))
        }

    /** A transport failure in step 1 does not abort: step 2/3 still deliver. */
    @Test
    fun a_failed_source_is_skipped_not_fatal() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.Failure(WiktionaryErrorCode.NETWORK, "es down") },
                    english = { _, _ -> WiktionaryFetch.Success(wikitext("en_juxtaposition.json")) },
                    translate = { text, _ -> "ES($text)" },
                ).define(request("juxtaposition"))

            assertEquals(DefinitionSource.Translated, result.source)
            assertNull(result.failure)
        }

    /**
     * A failed translation leaves the result empty rather than showing raw
     * English under a Spanish UI — the honest failure.
     */
    @Test
    fun a_failed_translation_yields_no_result_rather_than_raw_english() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.MissingPage },
                    english = { _, _ -> WiktionaryFetch.Success(wikitext("en_juxtaposition.json")) },
                    translate = { _, _ -> null },
                ).define(request("juxtaposition"))

            assertTrue(result.definitions.isEmpty())
            assertNull(result.source)
            assertNull("a missing translation is not a service failure", result.failure)
        }

    // ── The word language (T3) ────────────────────────────────────────────

    /** An unknown word language skips step 1 and assumes `en` for steps 2/3. */
    @Test
    fun unknown_word_language_skips_step_one_and_assumes_english() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> error("step 1 must be skipped when the language is unknown") },
                    english = { _, _ -> WiktionaryFetch.Success(wikitext("en_juxtaposition.json")) },
                    translate = { text, _ -> "ES($text)" },
                ).define(request("juxtaposition", wordLanguage = WordLanguage.unknown()))

            assertTrue(recorder.native.isEmpty())
            assertEquals(DefinitionSource.Translated, result.source)
            assertEquals("en", result.wordLanguage)
            assertFalse(result.wordLanguageKnown)
            assertTrue(result.definitions.isNotEmpty())
        }

    @Test
    fun an_empty_or_blank_language_code_reads_as_unknown() {
        assertFalse(WordLanguage.fromOptional("").known)
        assertFalse(WordLanguage.fromOptional("   ").known)
        assertFalse(WordLanguage.fromOptional(null).known)
        assertTrue(WordLanguage.fromOptional("en").known)
        assertEquals("en", WordLanguage.fromOptional(" en ").wordLanguage)
        // A BCP-47 tag normalises to its base; an unusable one is unknown.
        assertEquals("es", WordLanguage.fromOptional("es-419").wordLanguage)
        assertFalse(WordLanguage.fromOptional("!!").known)
    }

    /** The two languages are recorded separately and never swapped. */
    @Test
    fun the_word_language_and_the_reader_language_stay_distinct() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> WiktionaryFetch.Success(wikitext("es_house.json")) },
                    english = { _, _ -> WiktionaryFetch.MissingPage },
                ).define(request("house", readerLanguage = "es", wordLanguage = WordLanguage.known("en")))

            assertEquals("en", result.wordLanguage)
            assertEquals("es", result.readerLanguage)
        }

    @Test
    fun an_empty_word_is_a_failure_not_a_request() =
        runTest {
            val recorder = Recorder()
            val result =
                cascade(
                    recorder,
                    native = { _, _ -> error("must not be reached") },
                    english = { _, _ -> error("must not be reached") },
                ).define(request("   "))

            assertEquals(DefinitionErrorCode.EMPTY_WORD, result.failure?.code)
            assertTrue(recorder.native.isEmpty())
        }
}

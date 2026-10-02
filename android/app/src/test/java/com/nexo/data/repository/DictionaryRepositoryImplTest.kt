package com.nexo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.domain.model.DictionaryWord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryRepositoryImplTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DictionaryWordDao
    private lateinit var repository: DictionaryRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.dictionaryWordDao()
        repository = DictionaryRepositoryImpl(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFullEntry(word: String = "Efímero"): DictionaryWord =
        repository
            .save(
                word = word,
                definition = "Que dura poco tiempo",
                partOfSpeech = "adjetivo",
                phonetic = "eˈfimeɾo",
                example = "Un amor efímero.",
                quote = "Todo lo que nace está condenado a lo efímero.",
                sourceBookId = "book-1",
                sourceBookTitle = "La Odisea",
                sourceBookAuthor = "Homero",
                sourceChapter = "Canto I",
                sourceLocator = "epubcfi(/6/4!/2/10)",
            ).getOrThrow()

    private fun assertEvidenceIntact(stored: DictionaryWord) {
        assertEquals("Todo lo que nace está condenado a lo efímero.", stored.quote)
        assertEquals("book-1", stored.sourceBookId)
        assertEquals("La Odisea", stored.sourceBookTitle)
        assertEquals("Homero", stored.sourceBookAuthor)
        assertEquals("Canto I", stored.sourceChapter)
        assertEquals("epubcfi(/6/4!/2/10)", stored.sourceLocator)
    }

    @Test
    fun `save round-trips the user fields and the evidence columns`() =
        runBlocking {
            val saved = seedFullEntry()

            val stored = repository.observeAll().first().single()
            assertEquals(saved, stored)
            assertEquals("Efímero", stored.word)
            assertEquals("Que dura poco tiempo", stored.definition)
            assertEquals("adjetivo", stored.partOfSpeech)
            assertEquals("eˈfimeɾo", stored.phonetic)
            assertEquals("Un amor efímero.", stored.example)
            assertEvidenceIntact(stored)

            val searched = repository.search("Efímero").first().single()
            assertEvidenceIntact(searched)
        }

    @Test
    fun `save with a word only leaves the nine new fields null`() =
        runBlocking {
            repository.save("café").getOrThrow()

            val stored = repository.observeAll().first().single()
            assertEquals("café", stored.word)
            assertNull(stored.definition)
            assertNull(stored.partOfSpeech)
            assertNull(stored.phonetic)
            assertNull(stored.example)
            assertNull(stored.quote)
            assertNull(stored.sourceBookId)
            assertNull(stored.sourceBookTitle)
            assertNull(stored.sourceBookAuthor)
            assertNull(stored.sourceChapter)
            assertNull(stored.sourceLocator)
        }

    @Test
    fun `blank user fields are stored as null`() =
        runBlocking {
            repository.save("café", definition = "   ").getOrThrow()
            assertNull(
                repository
                    .observeAll()
                    .first()
                    .single()
                    .definition,
            )
        }

    @Test
    fun `updateUserFields rewrites the four user fields and leaves every evidence column untouched`() =
        runBlocking {
            val saved = seedFullEntry()

            val updated =
                repository
                    .updateUserFields(
                        wordId = saved.id,
                        definition = "Nueva definición",
                        partOfSpeech = "sustantivo",
                        phonetic = "nuevo",
                        example = "Nuevo ejemplo.",
                    ).getOrThrow()

            assertEquals("Nueva definición", updated.definition)
            assertEquals("sustantivo", updated.partOfSpeech)
            assertEquals("nuevo", updated.phonetic)
            assertEquals("Nuevo ejemplo.", updated.example)
            assertEquals(saved.word, updated.word)
            assertEquals(saved.addedAtEpochMillis, updated.addedAtEpochMillis)
            assertEvidenceIntact(updated)

            assertEvidenceIntact(repository.observeAll().first().single())
        }

    @Test
    fun `updateUserFields can clear the user fields without erasing evidence`() =
        runBlocking {
            val saved = seedFullEntry()

            val updated =
                repository
                    .updateUserFields(saved.id, definition = null, partOfSpeech = null, phonetic = null, example = null)
                    .getOrThrow()

            assertNull(updated.definition)
            assertNull(updated.partOfSpeech)
            assertNull(updated.phonetic)
            assertNull(updated.example)
            assertEvidenceIntact(updated)
        }

    @Test
    fun `exists matches across casing and accents`() =
        runBlocking {
            repository.save("Café").getOrThrow()

            assertTrue(repository.exists("Café"))
            assertTrue(repository.exists("café"))
            assertTrue(repository.exists("CAFÉ"))
            assertTrue(repository.exists("Cafe"))
            assertTrue(repository.exists("  Cafe  "))
            assertFalse(repository.exists("Cafeína"))
        }

    @Test
    fun `exists folds decomposable letters outside the Latin-1 set`() =
        runBlocking {
            repository.save("Tōkyō").getOrThrow()

            assertTrue(repository.exists("tokyo"))
            assertTrue(repository.exists("TŌKYŌ"))
        }

    @Test
    fun `exists keeps the punctuation contract and the stored surface form`() =
        runBlocking {
            repository.save("Abyss").getOrThrow()

            assertTrue(repository.exists("abyss"))
            assertFalse(repository.exists("Abyss,"))
            assertEquals(
                "Abyss",
                repository
                    .observeAll()
                    .first()
                    .single()
                    .word,
            )
        }

    @Test
    fun `exists matches punctuation when the stored form carries it too`() =
        runBlocking {
            repository.save("Hello,").getOrThrow()

            assertTrue(repository.exists("hello,"))
            assertEquals(
                "Hello,",
                repository
                    .observeAll()
                    .first()
                    .single()
                    .word,
            )
        }

    /**
     * FR-11 parity: the indexed `exists()` must agree with the retired in-memory
     * `allWords()` scan (here reproduced by normalizing every stored word) across
     * the shared REQ-DSI-004 vectors.
     */
    @Test
    fun `indexed exists returns identical results to the retired allWords scan`() =
        runBlocking {
            listOf("Café", "Abyss", "Hello,", "Tōkyō", "Ñandú", "Serendipity").forEach {
                repository.save(it).getOrThrow()
            }
            val probes =
                listOf(
                    "Café",
                    "café",
                    "CAFÉ",
                    "Cafe",
                    "  Cafe  ",
                    "Cafeína",
                    "abyss",
                    "Abyss,",
                    "hello,",
                    "tokyo",
                    "TŌKYŌ",
                    "nandu",
                    "serendipity",
                    "Serendipity!",
                    "missing",
                )

            val stored = dao.getAll()
            for (probe in probes) {
                val key = DictionaryNormalizer.normalize(probe)
                val oldScan = stored.any { DictionaryNormalizer.normalize(it.word) == key }
                assertEquals("exists($probe) must match the old scan", oldScan, repository.exists(probe))
            }
        }

    @Test
    fun `insert stamps the indexed normalized key`() =
        runBlocking {
            repository.save("  Tōkyō  ").getOrThrow()

            val stored = dao.getAll().single()
            assertEquals("tokyo", stored.wordNormalized)
        }

    /**
     * FR-11 replacement semantics: the indexed lookup is a prefix match on the
     * normalized word (the device-safe form of the old `LIKE '%q%'` scan). It is
     * accent/case-insensitive and intentionally does NOT match mid-word infixes.
     */
    @Test
    fun `indexed search matches by normalized prefix`() =
        runBlocking {
            repository.save("Café").getOrThrow()
            repository.save("Serendipity").getOrThrow()

            assertEquals(listOf("Café"), repository.search("caf").first().map { it.word })
            assertEquals(listOf("Café"), repository.search("CAF").first().map { it.word })
            assertEquals(listOf("Serendipity"), repository.search("Seren").first().map { it.word })
            assertTrue(repository.search("fé").first().isEmpty())
            assertTrue(repository.search("endi").first().isEmpty())
        }

    @Test
    fun `exists and search use the normalized index`() {
        val existsPlan =
            explain(
                "SELECT EXISTS(SELECT 1 FROM dictionary_words WHERE word_normalized = 'cafe' LIMIT 1)",
            )
        assertTrue(
            "exists() must use index_dictionary_words_word_normalized but plan was:\n$existsPlan",
            existsPlan.contains("index_dictionary_words_word_normalized"),
        )

        val searchPlan =
            explain(
                "SELECT * FROM dictionary_words WHERE word_normalized >= 'cafe' " +
                    "AND word_normalized < 'cafe' || char(1114111) ORDER BY addedAtEpochMillis DESC",
            )
        assertTrue(
            "search() must use index_dictionary_words_word_normalized but plan was:\n$searchPlan",
            searchPlan.contains("index_dictionary_words_word_normalized"),
        )
    }

    private fun explain(query: String): String {
        val details = mutableListOf<String>()
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $query").use { cursor ->
            val detailIndex = cursor.getColumnIndex("detail")
            while (cursor.moveToNext()) details.add(cursor.getString(detailIndex))
        }
        return details.joinToString("\n")
    }
}

package com.nexo.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Serializable
private data class CanonicalFixture(
    val input: String,
    val expectedStem: String,
)

@Serializable
private data class ReadableFixture(
    val title: String,
    val bookId: String,
    val extension: String,
    val expectedName: String,
)

@Serializable
private data class TruncationFixture(
    val title: String,
    val expectedSanitized: String,
)

@Serializable
private data class FixtureContract(
    val canonical: List<CanonicalFixture>,
    val readable: List<ReadableFixture>,
    val truncation: List<TruncationFixture>,
)

/**
 * Consumes the SAME `packages/drive-filename-fixtures/fixtures.json` as the Rust
 * and TypeScript runners, so a divergence fails the platform that drifted
 * instead of staying latent (INV-1 is executable).
 */
class DriveFilenameTest {
    private val contract: FixtureContract by lazy {
        JSON.decodeFromString(locateFixturesFile().readText())
    }

    private val fixtures: List<CanonicalFixture> get() = contract.canonical

    @Test
    fun `canonical matches the shared fixtures contract`() {
        assertTrue("the contract keeps at least 14 fixtures", fixtures.size >= 14)
        for (fixture in fixtures) {
            assertEquals(
                "canonical stem for input ${fixture.input}",
                fixture.expectedStem,
                DriveFilename.canonicalStem(fixture.input),
            )
            // Idempotence: re-sanitizing an already-canonical stem is a no-op.
            assertEquals(
                "re-sanitizing ${fixture.expectedStem} must be a no-op",
                fixture.expectedStem,
                DriveFilename.canonicalStem(fixture.expectedStem),
            )
        }
    }

    @Test
    fun `readable names match the shared fixtures contract`() {
        assertTrue("the readable surface keeps at least 7 fixtures", contract.readable.size >= 7)
        for (fixture in contract.readable) {
            assertEquals(
                "readable name for title ${fixture.title}",
                fixture.expectedName,
                DriveFilename.readableObjectName(fixture.title, fixture.bookId, fixture.extension),
            )
        }
    }

    @Test
    fun `truncation matches the shared fixtures contract`() {
        assertTrue("the truncation surface keeps at least 1 fixture", contract.truncation.isNotEmpty())
        for (fixture in contract.truncation) {
            assertEquals(
                "sanitized title for fixture ${fixture.title}",
                fixture.expectedSanitized,
                DriveFilename.sanitizeTitleForFilename(fixture.title),
            )
        }
    }

    @Test
    fun `canonical keeps the alphabet and length bound`() {
        val alphabet = Regex("^[a-z0-9_-]{1,120}$")
        for (fixture in fixtures) {
            assertTrue(
                "stem ${fixture.expectedStem} is outside the canonical alphabet",
                alphabet.matches(fixture.expectedStem),
            )
            assertTrue(
                "stem ${fixture.expectedStem} must not be a reserved device name",
                fixture.expectedStem.lowercase() !in RESERVED,
            )
        }
    }

    @Test
    fun `legacy forms cover every divergent sanitizer for a colon id`() {
        val forms = DriveFilename.legacyForms("gutendex:2701")
        assertTrue("canonical stem missing", "gutendex2701" in forms)
        assertTrue("dash sanitizer stem missing", "gutendex-2701" in forms)
        assertTrue("underscore sanitizer stem missing", "gutendex_2701" in forms)
        assertTrue("raw colon-bearing stem missing", "gutendex:2701" in forms)
    }

    @Test
    fun `legacy state names include the raw colon form`() {
        val names = DriveFilename.legacyStateNames("gutendex:2701")
        assertTrue("canonical state name missing", "gutendex2701_state.json" in names)
        assertTrue("raw colon state name missing", "gutendex:2701_state.json" in names)
    }

    @Test
    fun `object path canonicalizes user token and extension`() {
        val book = DriveFilename.canonical("gutendex:2701")
        assertEquals(
            "books/user-1/gutendex2701.epub",
            DriveFilename.objectPath("User-1", book, "EPUB"),
        )
    }

    @Test
    fun `parse drive path returns stem and extension`() {
        assertEquals(
            DriveFilename.ParsedDrivePath("gutendex2701", "epub"),
            DriveFilename.parseDrivePath("books/user1/gutendex2701.epub"),
        )
    }

    @Test
    fun `sanitize title replaces reserved and control characters and keeps unicode`() {
        assertEquals(
            "a b c d e f g h i j",
            DriveFilename.sanitizeTitleForFilename("a/b\\c:d*e?f\"g<h>i|j"),
        )
        assertEquals("multiple spaces", DriveFilename.sanitizeTitleForFilename("  multiple   spaces  "))
        assertEquals("line break tab", DriveFilename.sanitizeTitleForFilename("line\nbreak\ttab"))
        assertEquals("Moby Dick", DriveFilename.sanitizeTitleForFilename("Moby Dick. "))
        assertEquals("title", DriveFilename.sanitizeTitleForFilename("...title..."))
        // Unicode letters, case, and accents are preserved: the name is read.
        assertEquals(
            "Prisioneros de la geografía",
            DriveFilename.sanitizeTitleForFilename("Prisioneros de la geografía"),
        )
        assertEquals("", DriveFilename.sanitizeTitleForFilename("///"))
    }

    @Test
    fun `readable object name composes title separator and id`() {
        val uuid = "5b4ba1c1-1b6f-4301-816a-40385be603fa"
        assertEquals(
            "Prisioneros de la geografía -- $uuid.epub",
            DriveFilename.readableObjectName("Prisioneros de la geografía", uuid, "epub"),
        )
        // Empty title falls back to the id-only canonical name (no dangling separator).
        assertEquals(
            DriveFilename.objectName(uuid, "epub"),
            DriveFilename.readableObjectName("", uuid, "epub"),
        )
        // The extension is canonicalized.
        assertTrue(DriveFilename.readableObjectName("T", uuid, "PDF").endsWith(".pdf"))
    }

    @Test
    fun `parse readable stem recovers the id after the last separator`() {
        val uuid = "5b4ba1c1-1b6f-4301-816a-40385be603fa"
        assertEquals(uuid, DriveFilename.parseReadableStem("Title -- $uuid"))
        // A title that itself contains the separator still resolves to the id.
        assertEquals(uuid, DriveFilename.parseReadableStem("A -- B -- $uuid"))
        assertEquals(null, DriveFilename.parseReadableStem(uuid))
        assertEquals(null, DriveFilename.parseReadableStem("Title -- "))
    }

    private fun locateFixturesFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "packages/drive-filename-fixtures/fixtures.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("fixtures.json not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }

        val RESERVED: Set<String> =
            buildSet {
                addAll(listOf("con", "prn", "aux", "nul"))
                for (index in 1..9) {
                    add("com$index")
                    add("lpt$index")
                }
            }
    }
}

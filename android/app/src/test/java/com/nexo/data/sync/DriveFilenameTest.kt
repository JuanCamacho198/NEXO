package com.nexo.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Serializable
private data class FilenameFixture(
    val input: String,
    val expectedStem: String,
)

/**
 * Consumes the SAME `packages/drive-filename-fixtures/fixtures.json` as the Rust
 * and TypeScript runners, so a divergence fails the platform that drifted
 * instead of staying latent (INV-1 is executable).
 */
class DriveFilenameTest {
    private val fixtures: List<FilenameFixture> by lazy {
        JSON.decodeFromString(locateFixturesFile().readText())
    }

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

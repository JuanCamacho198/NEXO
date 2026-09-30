package com.nexo.ui.util

import com.nexo.domain.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class BookFormatUtilsTest {
    // ── helpers ────────────────────────────────────────────────────────

    private fun createTempFileWithSize(bytes: Long): File {
        val f = File.createTempFile("bfu_", ".bin")
        f.deleteOnExit()
        RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        return f
    }

    private fun book(
        format: String,
        totalPages: Int?,
    ): Book =
        Book(
            id = "b1",
            title = "T",
            author = null,
            coverPath = null,
            filePath = "/tmp/b.epub",
            format = format,
            totalPages = totalPages,
            updatedAtEpochMillis = 1L,
        )

    // ── formatSizeMb ─────────────────────────────────────────────────

    @Test
    fun `formatSizeMb null returns fallback`() {
        assertEquals("—", formatSizeMb(null))
        assertEquals("—", formatSizeMb(null, "—"))
    }

    @Test
    fun `formatSizeMb blank returns fallback`() {
        assertEquals("—", formatSizeMb(""))
        assertEquals("—", formatSizeMb("   "))
    }

    @Test
    fun `formatSizeMb custom fallback is respected`() {
        assertEquals("N/A", formatSizeMb(null, "N/A"))
        assertEquals("N/A", formatSizeMb("", "N/A"))
    }

    @Test
    fun `formatSizeMb missing file returns fallback`() {
        assertEquals("—", formatSizeMb("/nonexistent/path/xyz_12345.bin"))
        assertEquals("custom", formatSizeMb("/nonexistent/path/xyz_12345.bin", "custom"))
    }

    @Test
    fun `formatSizeMb empty file returns fallback`() {
        val f = createTempFileWithSize(0L)
        try {
            assertEquals("—", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb 1024 bytes formats with one decimal`() {
        // 1024 bytes = ~0.001 MB -> "0.0 MB" (Locale.US, one decimal)
        val f = createTempFileWithSize(1024L)
        try {
            assertEquals("0.0 MB", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb 1 MB formats as 1_0 MB`() {
        val f = createTempFileWithSize(1024L * 1024L)
        try {
            assertEquals("1.0 MB", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb small file below 100MB keeps one decimal`() {
        // 10 MB -> "10.0 MB"
        val f = createTempFileWithSize(10L * 1024L * 1024L)
        try {
            assertEquals("10.0 MB", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb large file at 100MB formats without decimal`() {
        val f = createTempFileWithSize(100L * 1024L * 1024L)
        try {
            assertEquals("100 MB", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb large file above 100MB truncates to int MB`() {
        // 150 MB -> "150 MB" (no decimal branch)
        val f = createTempFileWithSize(150L * 1024L * 1024L)
        try {
            assertEquals("150 MB", formatSizeMb(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `formatSizeMb result ends with MB when file exists`() {
        val f = createTempFileWithSize(2048L)
        try {
            assertTrue(formatSizeMb(f.absolutePath).endsWith("MB"))
        } finally {
            f.delete()
        }
    }

    // ── languageDisplayName ──────────────────────────────────────────

    @Test
    fun `languageDisplayName null and blank return fallback`() {
        assertEquals("—", languageDisplayName(null))
        assertEquals("—", languageDisplayName(""))
        assertEquals("—", languageDisplayName("   "))
        assertEquals("N/A", languageDisplayName(null, "N/A"))
    }

    @Test
    fun `languageDisplayName known code returns display name not raw code`() {
        val defaultLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val display = languageDisplayName("en")
            // Locale("en").getDisplayName(ENGLISH) = "English"
            assertEquals("English", display)
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `languageDisplayName spanish code returns display name`() {
        val defaultLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val display = languageDisplayName("es")
            // "Spanish" or "es" depending on JDK but should not be raw blank
            assertTrue(display.isNotBlank())
            // For known codes display differs from raw code
            assertTrue(display != "—")
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `languageDisplayName unknown code returns code itself`() {
        // Locale.forLanguageTag("xx") display equals "xx" -> fallback to code
        assertEquals("xx", languageDisplayName("xx"))
        assertEquals("zz", languageDisplayName("zz"))
    }

    @Test
    fun `languageDisplayName custom fallback for blank`() {
        assertEquals("unknown", languageDisplayName("", "unknown"))
        assertEquals("unknown", languageDisplayName(null, "unknown"))
    }

    // ── publishedYear ────────────────────────────────────────────────

    @Test
    fun `publishedYear extracts year from iso`() {
        assertEquals("2024", publishedYear("2024-01-15"))
        assertEquals("1937", publishedYear("1937-09-21"))
        assertEquals("2000", publishedYear("2000-12-31"))
    }

    @Test
    fun `publishedYear null returns fallback`() {
        assertEquals("—", publishedYear(null))
        assertEquals("N/A", publishedYear(null, "N/A"))
    }

    @Test
    fun `publishedYear blank returns fallback`() {
        assertEquals("—", publishedYear(""))
        assertEquals("—", publishedYear("   "))
    }

    @Test
    fun `publishedYear non-numeric prefix returns fallback`() {
        assertEquals("—", publishedYear("abcd-01-01"))
        assertEquals("—", publishedYear("20ab-01-01"))
        assertEquals("—", publishedYear("20-01-01"))
    }

    // ── getPagesDisplayText ──────────────────────────────────────────

    @Test
    fun `getPagesDisplayText pdf returns pages as string`() {
        assertEquals("42", getPagesDisplayText(book("pdf", 42)))
        assertEquals("1", getPagesDisplayText(book("pdf", 1)))
    }

    @Test
    fun `getPagesDisplayText epub returns estimated prefix`() {
        assertEquals("≈300", getPagesDisplayText(book("epub", 300)))
        assertEquals("≈1", getPagesDisplayText(book("epub", 1)))
    }

    @Test
    fun `getPagesDisplayText null pages returns fallback`() {
        assertEquals("—", getPagesDisplayText(book("pdf", null)))
        assertEquals("—", getPagesDisplayText(book("epub", null)))
        assertEquals("N/A", getPagesDisplayText(book("pdf", null), "N/A"))
    }

    @Test
    fun `getPagesDisplayText unknown format returns fallback`() {
        assertEquals("—", getPagesDisplayText(book("mobi", 100)))
        assertEquals("custom", getPagesDisplayText(book("unknown", 100), "custom"))
    }

    @Test
    fun `getPagesDisplayText custom fallback respected`() {
        assertEquals("—", getPagesDisplayText(book("pdf", null), "—"))
        assertEquals("-", getPagesDisplayText(book("epub", null), "-"))
    }
}

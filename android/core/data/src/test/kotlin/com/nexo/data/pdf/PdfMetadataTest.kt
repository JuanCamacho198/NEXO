package com.nexo.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfMetadataTest {
    @Test
    fun create_withAllFields() {
        val metadata =
            PdfMetadata(
                title = "Test Book",
                author = "Test Author",
                pageCount = 150,
                fileSizeBytes = 1024000L,
            )

        assertEquals("Test Book", metadata.title)
        assertEquals("Test Author", metadata.author)
        assertEquals(150, metadata.pageCount)
        assertEquals(1024000L, metadata.fileSizeBytes)
    }

    @Test
    fun create_withNullFields() {
        val metadata =
            PdfMetadata(
                title = null,
                author = null,
                pageCount = 0,
                fileSizeBytes = 0L,
            )

        assertEquals(null, metadata.title)
        assertEquals(null, metadata.author)
    }

    @Test
    fun copy_modifiesTitle() {
        val original =
            PdfMetadata(
                title = "Original",
                author = "Author",
                pageCount = 100,
                fileSizeBytes = 500L,
            )

        val copied = original.copy(title = "Modified")

        assertEquals("Modified", copied.title)
        assertEquals("Author", copied.author)
        assertEquals(100, copied.pageCount)
        assertEquals(500L, copied.fileSizeBytes)
    }

    @Test
    fun copy_modifiesAllFields() {
        val original =
            PdfMetadata(
                title = "Original",
                author = "Original Author",
                pageCount = 100,
                fileSizeBytes = 500L,
            )

        val copied =
            original.copy(
                title = "New Title",
                author = "New Author",
                pageCount = 200,
                fileSizeBytes = 1000L,
            )

        assertEquals("New Title", copied.title)
        assertEquals("New Author", copied.author)
        assertEquals(200, copied.pageCount)
        assertEquals(1000L, copied.fileSizeBytes)
    }
}

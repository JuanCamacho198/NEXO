package com.nexo.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * NEXO-ANDROID-P/Q regression: a `reading_progress` row whose `percentage`
 * column is NULL crashed Realtime deserialization in the reader, closing the
 * app. The Supabase client now coerces null into the field default
 * (`coerceInputValues = true`), so these rows must decode instead of throwing.
 */
class ReadingProgressRowNullToleranceTest {
    /** Mirrors the Supabase client serializer (SupabaseClientProvider). */
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    @Test fun `null percentage coerces to the field default`() {
        val payload =
            """
            {
              "id": "row-1",
              "user_id": "u-1",
              "book_id": "book-1",
              "cfi_location": "epubcfi(/6/4!/4/2/2)",
              "percentage": null,
              "locator_json": null,
              "updated_at": "2026-10-15T13:50:14+00:00",
              "version": 3
            }
            """.trimIndent()

        val row = json.decodeFromString<ReadingProgressRow>(payload)

        assertEquals("book-1", row.bookId)
        assertEquals(0.0, row.percentage, 0.0)
        assertEquals(3, row.version)
    }

    @Test fun `null percentage with null locator still decodes`() {
        val payload =
            """
            {
              "user_id": "u-1",
              "book_id": "book-2",
              "percentage": null,
              "locator_json": null,
              "updated_at": "2026-10-15T13:49:10+00:00"
            }
            """.trimIndent()

        val row = json.decodeFromString<ReadingProgressRow>(payload)

        assertEquals("book-2", row.bookId)
        assertEquals(0.0, row.percentage, 0.0)
        assertNotNull(row.updatedAt)
    }

    @Test fun `present percentage is preserved`() {
        val payload =
            """
            {
              "user_id": "u-1",
              "book_id": "book-3",
              "percentage": 42.5,
              "updated_at": "2026-10-15T13:50:14+00:00"
            }
            """.trimIndent()

        val row = json.decodeFromString<ReadingProgressRow>(payload)

        assertEquals(42.5, row.percentage, 0.0)
    }

    @Test fun `missing percentage falls back to the default`() {
        val payload =
            """
            {
              "user_id": "u-1",
              "book_id": "book-4",
              "updated_at": "2026-10-15T13:50:14+00:00"
            }
            """.trimIndent()

        val row = json.decodeFromString<ReadingProgressRow>(payload)

        assertEquals(0.0, row.percentage, 0.0)
    }
}

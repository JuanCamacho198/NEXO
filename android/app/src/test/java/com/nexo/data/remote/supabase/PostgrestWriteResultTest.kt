package com.nexo.data.remote.supabase

import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KType

/**
 * FIX 1 regression: PostgREST writes may return an empty body (204 No Content,
 * `Prefer: return=minimal`, or an RLS-stripped representation). The SDK's
 * `decodeSingleOrNull()` parses the body as a JSON array first and throws
 * `JsonDecodingException: Expected start of the array '[', but had 'EOF' instead
 * at path: $` on that empty body. The tolerant helper must treat the empty body
 * as "no representation" (null), keep reads strict, and never swallow a real
 * malformed/error body.
 */
class PostgrestWriteResultTest {
    @Serializable
    private data class Row(
        val id: String,
        val value: Int = 0,
    )

    /**
     * Minimal [SupabaseSerializer] backed by kotlinx.serialization's [Json],
     * mirroring what `postgrest-kt` supplies at runtime.
     */
    private class JsonSupabaseSerializer(
        private val json: Json = Json { ignoreUnknownKeys = true },
    ) : SupabaseSerializer {
        override fun <T> encode(
            type: KType,
            value: T,
        ): String = throw UnsupportedOperationException("encode is not exercised by these tests")

        @Suppress("UNCHECKED_CAST")
        override fun <T> decode(
            type: KType,
            value: String,
        ): T = json.decodeFromString(serializer(type), value) as T
    }

    private fun result(body: String): PostgrestResult {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns JsonSupabaseSerializer()
        return PostgrestResult(body, Headers.Empty, postgrest)
    }

    @Test
    fun `empty body decodes to null instead of throwing`() {
        assertNull(result("").decodeSingleOrNullTolerant<Row>())
    }

    @Test
    fun `whitespace-only body decodes to null instead of throwing`() {
        assertNull(result("  \n\t ").decodeSingleOrNullTolerant<Row>())
    }

    @Test
    fun `valid array body still decodes the single row`() {
        val row = result("""[{"id":"a","value":7}]""").decodeSingleOrNullTolerant<Row>()
        assertEquals(Row(id = "a", value = 7), row)
    }

    @Test
    fun `empty array body decodes to null`() {
        assertNull(result("[]").decodeSingleOrNullTolerant<Row>())
    }

    @Test
    fun `error object body is not swallowed`() {
        val errorBody = """{"code":"42P01","message":"relation does not exist"}"""
        try {
            result(errorBody).decodeSingleOrNullTolerant<Row>()
            fail("A non-empty malformed/error body must still throw")
        } catch (_: kotlinx.serialization.SerializationException) {
            // expected — genuine protocol errors are never swallowed
        }
    }
}

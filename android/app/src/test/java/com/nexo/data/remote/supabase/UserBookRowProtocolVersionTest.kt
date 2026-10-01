package com.nexo.data.remote.supabase

import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.encode
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * Cross-device wire-type contract for `user_books.protocol_version`.
 *
 * The live Supabase column is `text` (see
 * `supabase/migrations/20260806000001_cross_device_library_recovery.sql`): the
 * desktop client writes the string `"1"` and reads it back with `Number(...)`.
 * Android must therefore declare the DTO field as `String?` so the wire type
 * matches the column and the desktop writer, instead of relying on the JSON
 * decoder coercing a quoted string into an `Int`.
 *
 * These tests decode RAW JSON string bodies through the real [UserBookRow] DTO
 * and the serializer configuration the app actually runs. `SupabaseClientProvider`
 * installs `Postgrest` with no serializer override, so supabase-kt applies
 * `SupabaseClientBuilder.defaultSerializer`. In the resolved SDK (3.5.0) that is
 * `KotlinXSerializer(Json { ignoreUnknownKeys = true })`; no app-level central
 * serializer exists, so this IS the production configuration. `listUserBooks`,
 * `getUserBook`, `getUserBookByHash` and the upsert response all decode through
 * this DTO and serializer.
 */
class UserBookRowProtocolVersionTest {
    private val serializer: SupabaseSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true })

    @After
    fun tearDown() = io.mockk.unmockkAll()

    private fun result(body: String): PostgrestResult {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns serializer
        return PostgrestResult(body, Headers.Empty, postgrest)
    }

    private fun rowJson(protocolVersionLiteral: String): String =
        """[{"id":"b1","user_id":"u1","title":"T","format":"epub",""" +
            """"imported_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z",""" +
            """"protocol_version":$protocolVersionLiteral}]"""

    @Test
    fun `desktop-written text protocol_version decodes as string`() {
        val row = result(rowJson("\"1\"")).decodeList<UserBookRow>().single()
        assertEquals("1", row.protocolVersion)
    }

    @Test
    fun `null protocol_version decodes as null`() {
        val row = result(rowJson("null")).decodeList<UserBookRow>().single()
        assertNull(row.protocolVersion)
    }

    @Test
    fun `single-row tolerant decode also accepts text protocol_version`() {
        val row =
            result(rowJson("\"2\""))
                .decodeSingleOrNullTolerant<UserBookRow>()
        assertEquals("2", row?.protocolVersion)
    }

    @Test
    fun `outbound row serializes protocol_version as a json string`() {
        val row =
            UserBookRow(
                id = "b1",
                userId = "u1",
                title = "T",
                format = "epub",
                importedAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                protocolVersion = "3",
            )
        val encoded = serializer.encode(row)
        assertTrue(
            "protocol_version must travel as a quoted string, got: $encoded",
            encoded.contains("\"protocol_version\":\"3\""),
        )
    }
}

package com.nexo.data.remote.supabase

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * Decode a single row from a PostgREST response, tolerating an empty body.
 *
 * Applies to BOTH writes and single-row reads.
 *
 * Writes (`upsert` / `insert` / `update` / `delete`) may legally return:
 *  - `204 No Content` (the representation is stripped by RLS or an intermediary), or
 *  - a `201 Created` whose body is empty because the server was configured with
 *    `Prefer: return=minimal`.
 *
 * Reads (`select ... limit(1)`) look up "at most one row", so an empty body
 * simply means "no match" — a normal outcome, not a protocol error. The DELETE
 * branch of `processBookItem` reads through this path, which is how an empty
 * body there surfaced as a failed book deletion.
 *
 * The SDK's [PostgrestResult.decodeSingleOrNull] first decodes the body as a JSON
 * array (`typeOf<List<T>>()`), so an empty body throws a [kotlinx.serialization.SerializationException]
 * with `Expected start of the array '[', but had 'EOF' instead at path: $`.
 *
 * Callers get `null` on an empty/whitespace-only body: write callers fall back to
 * the row they sent (they already own the primary key), read callers treat it as
 * "not found". A non-empty but malformed body still throws, so genuine protocol
 * errors are never swallowed.
 */
internal inline fun <reified T : Any> PostgrestResult.decodeSingleOrNullTolerant(): T? = if (data.isBlank()) null else decodeSingleOrNull<T>()

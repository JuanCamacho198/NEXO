package com.nexo.data.sync

/**
 * FR-08 Drive binary write guard.
 *
 * Before overwriting a Drive binary the guard compares the writer's base marker
 * with the marker recorded on the object itself: version + content checksum live
 * in Drive `appProperties` (see [DriveWriteGuard.MARKER_VERSION_PROP] /
 * [DriveWriteGuard.MARKER_CHECKSUM_PROP]), so the marker rides the guarded
 * object and no sidecar file pollutes the listing. A mismatch throws a typed
 * [StaleWriteError] and the remote bytes stay untouched; a match uploads the
 * bytes and the bumped marker in one `files.update`, so a crash cannot desync
 * binary from marker.
 *
 * This is a CHECK, not a LOCK. Drive v3 `files.update` has no generation or
 * `If-Match` precondition, so two writers that pass the check concurrently can
 * still both write (last writer wins). The exposed window is the
 * read-check-write gap; it is documented here instead of hidden behind a lock
 * the API cannot provide.
 *
 * A base captured by an earlier sync makes a stale client fail closed. When no
 * base is known (`null`) the guard adopts the current remote version rather than
 * inventing a conflict, so a fresh/offline client still uploads and a missing
 * marker never becomes a permanent failure. [DriveWriteGuard.uploadWithRetry]
 * re-fetches the base, rebases on the remote the guard just observed, and
 * retries a bounded number of times for both stale and transient failures.
 */
data class DriveVersionMarker(
    val version: Long,
    val checksum: String,
)

/** Typed stale-write rejection carrying the base and the remote that beat it. */
class StaleWriteError(
    val name: CanonicalName,
    val objectName: String,
    val expectedVersion: Long,
    val actualVersion: Long,
) : Exception(
        "Stale write rejected for $objectName: base version $expectedVersion but remote is $actualVersion",
    )

/** Minimal Drive surface the guard needs; the data source implements it for real. */
interface DriveGuardPort {
    suspend fun readMarker(objectName: String): DriveVersionMarker?

    suspend fun writeBinary(
        objectName: String,
        bytes: ByteArray,
        marker: DriveVersionMarker,
    ): String
}

/** Result of a guarded write: the canonical object name, its marker, and file id. */
data class GuardedWrite(
    val objectName: String,
    val marker: DriveVersionMarker,
    val fileId: String,
)

object DriveWriteGuard {
    const val MARKER_VERSION_PROP = "nexoVersion"
    const val MARKER_CHECKSUM_PROP = "nexoChecksum"
    const val DEFAULT_MAX_ATTEMPTS = 3

    // FNV-1a 64-bit (0xcbf29ce484222325 / 0x100000001b3) as a signed Long;
    // `toULong()` renders the same hex the TypeScript guard produces.
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val BYTE_MASK = 0xFFL

    private val baseCache = mutableMapOf<String, DriveVersionMarker>()

    /** Canonical physical object name from a branded stem plus extension. */
    fun objectName(
        book: CanonicalName,
        extension: String,
    ): String = "${book.stem}.${DriveFilename.canonicalExtension(extension)}"

    /** FNV-1a 64-bit hex content checksum (matches the TypeScript guard). */
    fun checksum(bytes: ByteArray): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    fun appProperties(marker: DriveVersionMarker): Map<String, String> =
        mapOf(
            MARKER_VERSION_PROP to marker.version.toString(),
            MARKER_CHECKSUM_PROP to marker.checksum,
        )

    fun markerFromAppProperties(props: Map<String, String>?): DriveVersionMarker? {
        val raw = props?.get(MARKER_VERSION_PROP) ?: return null
        val version = raw.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return DriveVersionMarker(version, props[MARKER_CHECKSUM_PROP].orEmpty())
    }

    /** Per-object entry a later reconciler or layout migration can record in manifest.json. */
    fun manifestEntry(
        objectName: String,
        marker: DriveVersionMarker,
    ): Map<String, Any> = mapOf("name" to objectName, "version" to marker.version, "checksum" to marker.checksum)

    /** Seed the in-session base from a marker observed on a pull or a write. */
    fun rememberBase(
        objectName: String,
        marker: DriveVersionMarker?,
    ) {
        if (marker != null) baseCache[objectName] = marker
    }

    /** The last base this process saw for an object, or null when unknown. */
    fun recallBase(objectName: String): DriveVersionMarker? = baseCache[objectName]

    fun resetBaseCache() {
        baseCache.clear()
    }

    /**
     * Check-then-write one Drive binary. [base] is the version the caller's
     * content was based on (null = unknown, adopt remote). Throws
     * [StaleWriteError] without touching the remote when a known base fell behind.
     */
    suspend fun upload(
        port: DriveGuardPort,
        book: CanonicalName,
        extension: String,
        bytes: ByteArray,
        base: DriveVersionMarker?,
    ): GuardedWrite {
        val name = objectName(book, extension)
        val remote = port.readMarker(name)
        val remoteVersion = remote?.version ?: 0L
        if (base != null && remoteVersion != base.version) {
            throw StaleWriteError(book, name, base.version, remoteVersion)
        }
        val marker = DriveVersionMarker(remoteVersion + 1, checksum(bytes))
        val fileId = port.writeBinary(name, bytes, marker)
        rememberBase(name, marker)
        return GuardedWrite(name, marker, fileId)
    }

    /**
     * Bounded retry around [upload]. A stale rejection re-fetches the remote
     * marker and rebases before the next attempt; a transient I/O failure is
     * retried unchanged. Either way a transient failure is never promoted to a
     * permanent one — after the final attempt the last error is rethrown.
     */
    suspend fun uploadWithRetry(
        port: DriveGuardPort,
        book: CanonicalName,
        extension: String,
        bytes: ByteArray,
        loadBase: suspend () -> DriveVersionMarker? = { null },
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): GuardedWrite {
        var lastError: Throwable? = null
        var base = loadBase()
        repeat(maxAttempts) {
            try {
                return upload(port, book, extension, bytes, base)
            } catch (error: StaleWriteError) {
                base = runCatching { port.readMarker(error.objectName) }.getOrNull()
                lastError = error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("guarded upload failed")
    }
}

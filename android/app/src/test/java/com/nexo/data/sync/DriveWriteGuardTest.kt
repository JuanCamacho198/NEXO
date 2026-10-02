package com.nexo.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FR-08 write-guard scenarios: stale writer rejected with remote bytes
 * untouched, fresh writer bumps binary + marker atomically, unknown base adopts
 * the remote, and the retry path rebases rather than failing permanently.
 */
class DriveWriteGuardTest {
    private class FakeDrive : DriveGuardPort {
        val objects = mutableMapOf<String, Pair<ByteArray, DriveVersionMarker>>()
        var writes = 0
        var writeFailures = 0

        override suspend fun readMarker(objectName: String): DriveVersionMarker? = objects[objectName]?.second

        override suspend fun writeBinary(
            objectName: String,
            bytes: ByteArray,
            marker: DriveVersionMarker,
        ): String {
            if (writeFailures > 0) {
                writeFailures--
                error("transient network drop")
            }
            writes++
            objects[objectName] = bytes to marker
            return "id-$objectName"
        }
    }

    private val book = DriveFilename.canonical("gutendex:2701")
    private val objectName = "gutendex2701.epub"
    private lateinit var drive: FakeDrive

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @Before
    fun setUp() {
        drive = FakeDrive()
        DriveWriteGuard.resetBaseCache()
    }

    @Test
    fun `stale writer is rejected and the remote binary is unchanged`() =
        runTest {
            drive.objects[objectName] =
                bytes(1, 2, 3) to DriveVersionMarker(version = 2, checksum = "aa")

            val error =
                runCatching {
                    DriveWriteGuard.upload(
                        port = drive,
                        book = book,
                        extension = "epub",
                        bytes = bytes(9),
                        base = DriveVersionMarker(version = 1, checksum = "aa"),
                    )
                }.exceptionOrNull()

            assertTrue(error is StaleWriteError)
            assertEquals(1L, (error as StaleWriteError).expectedVersion)
            assertEquals(2L, error.actualVersion)
            assertEquals(0, drive.writes)
            assertEquals(
                listOf<Byte>(1, 2, 3),
                drive.objects
                    .getValue(objectName)
                    .first
                    .toList(),
            )
        }

    @Test
    fun `fresh writer updates the binary and version marker atomically`() =
        runTest {
            drive.objects[objectName] =
                bytes(1) to DriveVersionMarker(version = 1, checksum = "old")

            val result =
                DriveWriteGuard.upload(
                    port = drive,
                    book = book,
                    extension = "epub",
                    bytes = bytes(4, 5),
                    base = DriveVersionMarker(version = 1, checksum = "old"),
                )

            assertEquals(2L, result.marker.version)
            assertEquals(DriveWriteGuard.checksum(bytes(4, 5)), result.marker.checksum)
            assertEquals(1, drive.writes)
            assertEquals(
                bytes(4, 5).toList(),
                drive.objects
                    .getValue(objectName)
                    .first
                    .toList(),
            )
            assertEquals(result.marker, drive.objects.getValue(objectName).second)
        }

    @Test
    fun `no remote marker starts at version one`() =
        runTest {
            val result =
                DriveWriteGuard.upload(drive, book, "epub", bytes(7), base = null)
            assertEquals(1L, result.marker.version)
        }

    @Test
    fun `unknown base adopts the remote version instead of failing closed`() =
        runTest {
            drive.objects[objectName] =
                bytes(1) to DriveVersionMarker(version = 5, checksum = "x")

            val result = DriveWriteGuard.upload(drive, book, "epub", bytes(2), base = null)

            assertEquals(6L, result.marker.version)
        }

    @Test
    fun `retry rebases on a stale rejection and succeeds`() =
        runTest {
            drive.objects[objectName] =
                bytes(1) to DriveVersionMarker(version = 2, checksum = "bb")

            val result =
                DriveWriteGuard.uploadWithRetry(
                    port = drive,
                    book = book,
                    extension = "epub",
                    bytes = bytes(3),
                    loadBase = { DriveVersionMarker(version = 1, checksum = "aa") },
                )

            assertEquals(3L, result.marker.version)
            assertEquals(1, drive.writes)
        }

    @Test
    fun `retry keeps a transient upload failure from becoming permanent`() =
        runTest {
            drive.writeFailures = 1

            val result =
                DriveWriteGuard.uploadWithRetry(drive, book, "epub", bytes(8), loadBase = { null })

            assertEquals(1L, result.marker.version)
            assertEquals(1, drive.writes)
        }

    @Test
    fun `marker round-trips through app properties and exposes a manifest entry`() {
        val marker = DriveVersionMarker(version = 4, checksum = "deadbeef")
        assertEquals(
            mapOf(
                DriveWriteGuard.MARKER_VERSION_PROP to "4",
                DriveWriteGuard.MARKER_CHECKSUM_PROP to "deadbeef",
            ),
            DriveWriteGuard.appProperties(marker),
        )
        assertEquals(marker, DriveWriteGuard.markerFromAppProperties(DriveWriteGuard.appProperties(marker)))
        assertEquals(null, DriveWriteGuard.markerFromAppProperties(null))
        assertEquals(
            mapOf("name" to objectName, "version" to 2L, "checksum" to "ab"),
            DriveWriteGuard.manifestEntry(objectName, DriveVersionMarker(2, "ab")),
        )
    }
}

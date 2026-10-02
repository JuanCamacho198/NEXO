package com.nexo.data.sync

import com.nexo.data.remote.sync.StorageSyncRemoteDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-07 reconciliation scenarios: adopt, copy-to-canonical, twins never deleted,
 * checksum mismatch flagged, and verification failure leaves the source in place.
 */
class DriveReconcilerTest {
    private val book = DriveReconciler.BookRef(bookId = "gutendex:2701", extension = "epub")
    private val canonical = "gutendex2701.epub"
    private val prefix = "books/user1/"

    private class FakeDrive(
        entries: List<Pair<String, ByteArray>>,
    ) : StorageSyncRemoteDataSource {
        val objects: MutableMap<String, ByteArray> =
            entries.associateTo(mutableMapOf()) { it.first to it.second }
        var failUpload: Boolean = false
        var corruptTarget: Boolean = false

        override suspend fun upload(
            path: String,
            bytes: ByteArray,
        ) {
            if (failUpload) error("upload refused")
            objects[path.substringAfterLast('/')] = if (corruptTarget) byteArrayOf(9, 9, 9) else bytes
        }

        override suspend fun download(path: String): ByteArray = objects[path.substringAfterLast('/')] ?: error("missing $path")

        override suspend fun list(prefix: String): List<String> = objects.keys.map { prefix + it }

        override suspend fun getFileSize(path: String): Long? = objects[path.substringAfterLast('/')]?.size?.toLong()
    }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @Test
    fun `plan adopts a canonical object with no legacy twin`() {
        val actions =
            DriveReconciler.plan(listOf(book), listOf(DriveReconciler.DriveObject(canonical)))
        assertEquals(listOf(DriveReconciler.Action.Adopt("gutendex:2701", canonical)), actions)
    }

    @Test
    fun `plan copies a legacy android dash name to canonical`() {
        val actions =
            DriveReconciler.plan(listOf(book), listOf(DriveReconciler.DriveObject("gutendex-2701.epub")))
        assertEquals(
            listOf(
                DriveReconciler.Action.CopyToCanonical(
                    bookId = "gutendex:2701",
                    sourceName = "gutendex-2701.epub",
                    canonicalName = canonical,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `plan copies a legacy underscore name to canonical`() {
        val actions =
            DriveReconciler.plan(listOf(book), listOf(DriveReconciler.DriveObject("gutendex_2701.epub")))
        assertEquals("gutendex_2701.epub", (actions.single() as DriveReconciler.Action.CopyToCanonical).sourceName)
    }

    @Test
    fun `plan recognizes a raw colon-bearing desktop name`() {
        val actions =
            DriveReconciler.plan(listOf(book), listOf(DriveReconciler.DriveObject("gutendex:2701.epub")))
        assertEquals("gutendex:2701.epub", (actions.single() as DriveReconciler.Action.CopyToCanonical).sourceName)
    }

    @Test
    fun `plan keeps canonical live and never deletes a matching twin`() {
        val actions =
            DriveReconciler.plan(
                listOf(book),
                listOf(
                    DriveReconciler.DriveObject(canonical, checksum = "aa"),
                    DriveReconciler.DriveObject("gutendex-2701.epub", checksum = "aa"),
                ),
            )
        assertEquals(
            listOf(DriveReconciler.Action.KeepCanonical("gutendex:2701", canonical, "gutendex-2701.epub")),
            actions,
        )
    }

    @Test
    fun `plan flags a checksum mismatch while keeping canonical live`() {
        val actions =
            DriveReconciler.plan(
                listOf(book),
                listOf(
                    DriveReconciler.DriveObject(canonical, checksum = "aa"),
                    DriveReconciler.DriveObject("gutendex-2701.epub", checksum = "bb"),
                ),
            )
        assertTrue(actions.single() is DriveReconciler.Action.FlagMismatch)
    }

    @Test
    fun `plan emits one canonical target and no delete`() {
        val actions =
            DriveReconciler.plan(
                listOf(book),
                listOf(
                    DriveReconciler.DriveObject("gutendex-2701.epub"),
                    DriveReconciler.DriveObject("gutendex_2701.epub"),
                ),
            )
        assertEquals(1, actions.size)
        assertTrue(actions.single() is DriveReconciler.Action.CopyToCanonical)
    }

    @Test
    fun `reconcile copies a legacy object, verifies, and keeps the source`() =
        runTest {
            val drive = FakeDrive(listOf("gutendex-2701.epub" to bytes(1, 2, 3)))

            val outcome = DriveReconciler.reconcile(drive, prefix, listOf(book))

            assertEquals(listOf(canonical), outcome.copied)
            assertTrue(outcome.failed.isEmpty())
            assertTrue(drive.objects.containsKey("gutendex-2701.epub"))
            assertTrue(drive.objects.getValue(canonical).contentEquals(bytes(1, 2, 3)))
        }

    @Test
    fun `reconcile does not copy when canonical and legacy twins already exist`() =
        runTest {
            val drive =
                FakeDrive(
                    listOf(
                        canonical to bytes(1, 2, 3),
                        "gutendex-2701.epub" to bytes(1, 2, 3),
                    ),
                )

            val outcome = DriveReconciler.reconcile(drive, prefix, listOf(book))

            assertTrue(outcome.copied.isEmpty())
            assertTrue(drive.objects.containsKey("gutendex-2701.epub"))
            assertTrue(outcome.actions.single() is DriveReconciler.Action.KeepCanonical)
        }

    @Test
    fun `reconcile reports a verification failure and preserves the source`() =
        runTest {
            val drive = FakeDrive(listOf("gutendex-2701.epub" to bytes(1, 2, 3)))
            drive.corruptTarget = true

            val outcome = DriveReconciler.reconcile(drive, prefix, listOf(book))

            assertTrue(outcome.copied.isEmpty())
            assertEquals("gutendex-2701.epub", outcome.failed.single().source)
            assertTrue(drive.objects.containsKey("gutendex-2701.epub"))
        }
}

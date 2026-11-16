package com.nexo.data.storage

import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T7/T8: covers are written under the active account's own subtree, and the
 * anonymous/local session writes nothing at all (decision D5).
 */
class AppInternalCoverStorageTest {
    private lateinit var filesDir: File
    private lateinit var activeUser: ActiveUserProvider
    private lateinit var storage: AppInternalCoverStorage

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("nexo-cover-storage").toFile()
        activeUser = ActiveUserProvider()
        storage =
            AppInternalCoverStorage(
                context = mockk { every { filesDir } returns this@AppInternalCoverStorageTest.filesDir },
                activeUser = activeUser,
            )
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun saveCover_landsInTheActiveAccountsSubtree() =
        runBlocking {
            activeUser.set(TEST_USER_ID)

            val path = storage.saveCover("book-1", byteArrayOf(1, 2, 3)).getOrThrow()

            val expectedDir = File(filesDir, "covers/$TEST_USER_ID")
            assertTrue(
                "cover must land in the account subtree, was: $path",
                File(path).parentFile.canonicalFile == expectedDir.canonicalFile,
            )
            assertTrue(File(path).isFile)
        }

    @Test
    fun twoAccounts_saveToDifferentFiles() =
        runBlocking {
            activeUser.set(TEST_USER_ID)
            val first = storage.saveCover("book-1", byteArrayOf(1)).getOrThrow()

            activeUser.set(OTHER_USER_ID)
            val second = storage.saveCover("book-1", byteArrayOf(2)).getOrThrow()

            assertFalse("the same book id must not resolve to the same file across accounts", first == second)
            assertTrue(File(first).isFile)
            assertTrue(File(second).isFile)
        }

    @Test
    fun anonymousSession_writesNothingToDisk() =
        runBlocking {
            activeUser.set("local-${java.util.UUID.randomUUID()}")

            val result = storage.saveCover("book-1", byteArrayOf(1, 2, 3))

            assertTrue("the anonymous path must not persist (D5)", result.isFailure)
            assertTrue(
                "no file may be created for the anonymous session",
                filesDir.walkTopDown().none { it.isFile },
            )
        }

    @Test
    fun signedOutSession_writesNothingToDisk() =
        runBlocking {
            val result = storage.saveCover("book-1", byteArrayOf(1, 2, 3))

            assertTrue(result.isFailure)
            assertTrue(filesDir.walkTopDown().none { it.isFile })
        }

    @Test
    fun deleteCover_isAnAnonymousNoOp() =
        runBlocking {
            activeUser.set(TEST_USER_ID)
            val path = storage.saveCover("book-1", byteArrayOf(1)).getOrThrow()

            activeUser.set("local-abc")
            storage.deleteCover("book-1").getOrThrow()

            assertTrue("the anonymous path must not delete another account's cover", File(path).isFile)
        }
}

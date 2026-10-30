package com.nexo.data.storage

import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T8: every kind of managed file resolves under the owning account's own
 * subtree, so two accounts never share a directory or a file name.
 */
class AccountStorageLayoutTest {
    private lateinit var filesDir: File
    private lateinit var layout: AccountStorageLayout

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("nexo-storage-layout").toFile()
        layout = AccountStorageLayout(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun twoUserIds_resolveToDifferentDirectories() {
        val kinds =
            listOf(
                AccountStorageLayout.BOOKS_DIR,
                AccountStorageLayout.COVERS_DIR,
                AccountStorageLayout.PDFS_DIR,
                AccountStorageLayout.EPUBS_DIR,
            )

        for (kind in kinds) {
            assertNotEquals(
                "kind=$kind must not share a directory across accounts",
                layout.dirFor(kind, TEST_USER_ID),
                layout.dirFor(kind, OTHER_USER_ID),
            )
        }
    }

    @Test
    fun twoUserIds_resolveToDifferentFileNames() {
        assertNotEquals(
            layout.bookFile(TEST_USER_ID, "book-1", "epub"),
            layout.bookFile(OTHER_USER_ID, "book-1", "epub"),
        )
        assertNotEquals(
            layout.coverFile(TEST_USER_ID, "book-1"),
            layout.coverFile(OTHER_USER_ID, "book-1"),
        )
        assertNotEquals(
            layout.namedFile(AccountStorageLayout.PDFS_DIR, TEST_USER_ID, "thesis.pdf"),
            layout.namedFile(AccountStorageLayout.PDFS_DIR, OTHER_USER_ID, "thesis.pdf"),
        )
    }

    @Test
    fun paths_areNamespacedByTheOwnerSegment() {
        assertEquals(
            File(filesDir, "books/$TEST_USER_ID").path,
            layout.booksDir(TEST_USER_ID).path,
        )
        assertEquals(
            File(filesDir, "covers/$TEST_USER_ID/book-1.jpg").path,
            layout.coverFile(TEST_USER_ID, "book-1").path,
        )
        assertEquals(
            File(filesDir, "epubs/$TEST_USER_ID/novel.epub").path,
            layout.namedFile(AccountStorageLayout.EPUBS_DIR, TEST_USER_ID, "novel.epub").path,
        )
    }

    @Test
    fun managedDirs_coversEveryAccountScopedKind() {
        val names = layout.managedDirs(TEST_USER_ID).map { it.parentFile.name }.toSet()

        assertEquals(
            setOf(
                AccountStorageLayout.BOOKS_DIR,
                AccountStorageLayout.COVERS_DIR,
                AccountStorageLayout.PDFS_DIR,
                AccountStorageLayout.EPUBS_DIR,
            ),
            names,
        )
    }

    @Test
    fun anonymousOwner_doesNotResolveToARealAccountDirectory() {
        assertNotEquals(layout.booksDir(""), layout.booksDir(TEST_USER_ID))
        assertNotEquals(layout.booksDir(""), layout.booksDir(OTHER_USER_ID))
        assertTrue(layout.booksDir("").path.endsWith(AccountStorageLayout.ANONYMOUS_SEGMENT))
    }

    @Test
    fun isInsideUserTree_detectsOwnSubtree() {
        val mine = layout.bookFile(TEST_USER_ID, "b", "epub").path
        val theirs = layout.bookFile(OTHER_USER_ID, "b", "epub").path

        assertTrue(layout.isInsideUserTree(mine, TEST_USER_ID))
        assertFalse("a file in another account's subtree is out of bounds", layout.isInsideUserTree(theirs, TEST_USER_ID))
        assertFalse(layout.isInsideUserTree(File(filesDir, "books/orphan.epub").path, TEST_USER_ID))
    }

    @Test
    fun legacyDir_isThePreAccountFlatDirectory() {
        assertEquals(File(filesDir, "covers").path, layout.legacyDir(AccountStorageLayout.COVERS_DIR).path)
        assertNotEquals(layout.legacyDir(AccountStorageLayout.COVERS_DIR), layout.coversDir(TEST_USER_ID))
    }
}

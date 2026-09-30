package com.nexo.data.remote.sync

import com.google.api.client.http.AbstractInputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.OutputStream

/** Unit tests for [GoogleDriveStorageRemoteDataSource] (D1 desktop protocol). */
class GoogleDriveStorageRemoteDataSourceTest {
    private lateinit var files: Drive.Files
    private val listResults = mutableListOf<FileList>()
    private var downloadBytes: ByteArray = byteArrayOf()

    @Before fun setUp() {
        files = mockk()
        listResults.clear()
        downloadBytes = byteArrayOf()
        GoogleDriveStorageRemoteDataSource.__resetGDriveFolderCache()
    }

    @After fun tearDown() = unmockkAll()

    private fun source(): GoogleDriveStorageRemoteDataSource {
        val drive = mockk<Drive>()
        val list = mockk<Drive.Files.List>()
        every { list.setQ(any()) } returns list
        every { list.setSpaces(any()) } returns list
        every { list.setFields(any()) } returns list
        every { list.execute() } answers { listResults.removeAt(0) }
        every { files.list() } returns list
        val folder = mockk<Drive.Files.Create>()
        val folderIds = ArrayDeque(listOf("next", "books"))
        every { folder.setFields(any()) } returns folder
        every { folder.execute() } answers { File().setId(folderIds.removeFirst()) }
        every { files.create(any<File>()) } returns folder
        val file = mockk<Drive.Files.Create>()
        every { file.setFields(any()) } returns file
        every { file.execute() } answers { File().setId("f1") }
        every { files.create(any<File>(), any<AbstractInputStreamContent>()) } returns file
        val get = mockk<Drive.Files.Get>()
        every { get.executeMediaAndDownloadTo(any()) } answers {
            firstArg<OutputStream>().write(downloadBytes)
        }
        every { files.get(any()) } returns get
        every { drive.files() } returns files
        return GoogleDriveStorageRemoteDataSource(drive)
    }

    private fun id(id: String) = FileList().apply { files = listOf(File().setId(id)) }

    private fun empty() = FileList().apply { files = emptyList() }

    @Test fun upload_createsFolderAndFileByName() =
        runBlocking {
            repeat(4) { listResults.add(empty()) }
            source().upload("books/u1/b.pdf", "c".toByteArray())
            verify(exactly = 2) { files.create(any<File>()) }
            verify { files.create(match { it.name == "b.pdf" }, any<AbstractInputStreamContent>()) }
        }

    @Test fun download_byName_returnsBytes() =
        runBlocking {
            listResults.add(id("next"))
            listResults.add(id("books"))
            listResults.add(FileList().apply { files = listOf(File().setId("f1").setName("b.pdf")) })
            downloadBytes = "hi".toByteArray()
            assertEquals("hi", String(source().download("books/u1/b.pdf")))
        }

    @Test fun list_mapsNamesToLogicalPaths() =
        runBlocking {
            listResults.add(id("next"))
            listResults.add(id("books"))
            listResults.add(FileList().apply { files = listOf(File().setName("a.epub"), File().setName("b.pdf")) })
            assertEquals(listOf("books/u1/a.epub", "books/u1/b.pdf"), source().list("books/u1/"))
        }

    @Test fun secondUpload_reusesResolvedFolder_noDuplicateFolders() =
        runBlocking {
            // First upload: find NextPage (empty) → create; find Books (empty) → create; find file (empty) → upload.
            // Second upload (cache hit): find file (empty) → upload — NO folder creations.
            repeat(5) { listResults.add(empty()) }
            val src = source()
            src.upload("books/u1/a.epub", "a".toByteArray())
            src.upload("books/u1/b.epub", "b".toByteArray())
            // Exactly TWO folder creates total (one NextPage + one Books), never four.
            verify(exactly = 2) { files.create(any<File>()) }
            verify(exactly = 2) { files.create(match { it.name == "a.epub" || it.name == "b.epub" }, any<AbstractInputStreamContent>()) }
        }
}

package com.nexo.domain.usecase

import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFileDownloader
import com.nexo.domain.model.Book
import com.nexo.domain.model.DuplicateBookException
import com.nexo.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression evidence for the download → import bridge: duplicate pre-check,
 * ordered progress, partial-file cleanup, cancellation and duplicate mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAndImportBookUseCaseTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun catalogBook(id: String = "gutendex:1342") =
        CatalogBook(
            id = id,
            provider = BUILTIN_GUTENDEX,
            title = "Pride and Prejudice",
            authors = listOf("Jane Austen"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = "https://www.gutenberg.org/ebooks/1342.epub",
        )

    private fun importedBook() =
        Book(
            id = "book-1",
            title = "Pride and Prejudice",
            author = "Jane Austen",
            coverPath = null,
            filePath = "/data/books/book-1.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
        )

    private class FakeDownloader(
        private val payload: ByteArray = ByteArray(16) { it.toByte() },
        private val failAtChunk: Int? = null,
        private val hangAfterFirstChunk: Boolean = false,
    ) : CatalogFileDownloader {
        var invocations = 0
            private set
        val destinations = mutableListOf<File>()

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            invocations++
            destinations += destination
            destination.parentFile?.mkdirs()
            val chunkSize = 4
            var written = 0
            var chunks = 0
            while (written < payload.size) {
                chunks++
                if (failAtChunk != null && chunks > failAtChunk) {
                    throw CatalogException(CatalogErrorCode.NETWORK_ERROR, "stream failed")
                }
                val end = minOf(written + chunkSize, payload.size)
                destination.appendBytes(payload.copyOfRange(written, end))
                written = end
                onProgress(written.toLong(), payload.size.toLong())
                if (hangAfterFirstChunk && chunks == 1) awaitCancellation()
            }
        }
    }

    private fun useCase(
        downloader: CatalogFileDownloader,
        importUseCase: ImportEpubBookUseCase,
        repo: LibraryRepository,
        tempDir: File,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = DownloadAndImportBookUseCase(
        downloader = downloader,
        importEpubBookUseCase = importUseCase,
        libraryRepository = repo,
        tempDir = tempDir,
        ioDispatcher = dispatcher,
    )

    @Test
    fun duplicatePreCheck_emitsDuplicate_andNeverDownloads() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns importedBook()
            val downloader = FakeDownloader()
            val importUseCase = mockk<ImportEpubBookUseCase>()
            val tempDir = tempFolder.newFolder("catalog")

            val states = useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()

            assertTrue(states.last() is DownloadImportState.Duplicate)
            assertEquals(0, downloader.invocations)
            coVerify(exactly = 0) { importUseCase.invoke(any(), any()) }
        }

    @Test
    fun success_emitsIdleDownloadingImportingSuccess() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns null
            val downloader = FakeDownloader()
            val importUseCase = mockk<ImportEpubBookUseCase>()
            coEvery { importUseCase.invoke(any(), any()) } returns Result.success(importedBook())
            val tempDir = tempFolder.newFolder("catalog")

            val states = useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()

            assertEquals(DownloadImportState.Idle, states.first())
            assertTrue(states.getOrNull(1) is DownloadImportState.Downloading)
            assertTrue(states.any { it is DownloadImportState.Importing })
            assertTrue(states.last() is DownloadImportState.Success)
            assertTrue(
                states.indexOfFirst { it is DownloadImportState.Importing } <
                    states.indexOfLast { it is DownloadImportState.Success },
            )
        }

    @Test
    fun downloadFailure_emitsFailureWithCode_andDeletesPartFile() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns null
            val downloader = FakeDownloader(failAtChunk = 1)
            val importUseCase = mockk<ImportEpubBookUseCase>()
            val tempDir = tempFolder.newFolder("catalog")

            val states = useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()

            val failure = states.last()
            assertTrue(failure is DownloadImportState.Failure)
            assertEquals(CatalogErrorCode.NETWORK_ERROR, (failure as DownloadImportState.Failure).error)
            assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
            assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".epub") })
            coVerify(exactly = 0) { importUseCase.invoke(any(), any()) }
        }

    @Test
    fun cancellation_deletesTemp_andIsNotMappedToFailure() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns null
            val downloader = FakeDownloader(hangAfterFirstChunk = true)
            val importUseCase = mockk<ImportEpubBookUseCase>()
            val tempDir = tempFolder.newFolder("catalog")

            val states = mutableListOf<DownloadImportState>()
            val job =
                launch(dispatcher) {
                    useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).collect {
                        states += it
                    }
                }
            advanceUntilIdle()
            job.cancel()
            job.join()

            assertTrue(states.none { it is DownloadImportState.Failure })
            assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
            coVerify(exactly = 0) { importUseCase.invoke(any(), any()) }
        }

    @Test
    fun importFailure_withDuplicateException_mapsToDuplicate() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns null
            val downloader = FakeDownloader()
            val importUseCase = mockk<ImportEpubBookUseCase>()
            coEvery { importUseCase.invoke(any(), any()) } returns Result.failure(DuplicateBookException())
            val tempDir = tempFolder.newFolder("catalog")

            val states = useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()

            assertTrue(states.last() is DownloadImportState.Duplicate)
            assertTrue(states.none { it is DownloadImportState.Failure })
        }

    @Test
    fun partFileName_isDeterministicPerBookId() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repo = mockk<LibraryRepository>()
            coEvery { repo.findBookByTitleAndAuthor(any(), any()) } returns null
            val downloader = FakeDownloader()
            val importUseCase = mockk<ImportEpubBookUseCase>()
            coEvery { importUseCase.invoke(any(), any()) } returns Result.success(importedBook())
            val tempDir = tempFolder.newFolder("catalog")

            useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()
            useCase(downloader, importUseCase, repo, tempDir, dispatcher)(catalogBook()).toList()

            val names = downloader.destinations.map { it.name }
            assertEquals(2, names.size)
            assertEquals(names[0], names[1])
            assertTrue(names[0].endsWith(".part"))
            assertTrue(names[0].startsWith("gutendex_1342"))
        }
}

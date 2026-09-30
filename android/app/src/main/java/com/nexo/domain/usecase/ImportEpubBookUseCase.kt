package com.nexo.domain.usecase

import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.repository.LibraryRepository
import java.io.InputStream

class ImportEpubBookUseCase(
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(
        request: BookImportRequest,
        inputStreamProvider: suspend () -> InputStream?,
    ): Result<Book> {
        // A6 - import timing: one pair around the existing suspend call on a
        // background dispatcher; heavy work is unchanged. A monotonic JVM clock
        // keeps the domain free of `android.os.SystemClock` while preserving the
        // measured duration (SDD android-stack-modernization S3, R7).
        val start = System.nanoTime()
        val result =
            libraryRepository.importBookFromEpub(
                request = request,
                inputStreamProvider = inputStreamProvider,
            )
        runCatching {
            val elapsed = (System.nanoTime() - start) / NANOS_PER_MILLI

            val exception = result.exceptionOrNull()
            val tags =
                mutableMapOf(
                    "source" to "import",
                    "format" to "epub",
                    "platform" to "android",
                )
            if (exception != null) {
                // Truncated application error code only — never the exception
                // message, which can embed user content.
                tags["error_code"] = exception.javaClass.simpleName.take(8)
            }
            com.nexo.debug.SentryMetrics.distribution(
                "book_import",
                com.nexo.debug.SentryMetrics
                    .bucketDurationMs(elapsed),
                tags,
            )
        }
        return result
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

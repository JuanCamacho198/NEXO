package com.nexo.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class DefaultPdfParserService(
    private val context: Context,
) : PdfParserService {
    override suspend fun extractMetadata(file: File): Result<PdfMetadata> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fileDescriptor)

                try {
                    val title = file.nameWithoutExtension
                    val author: String? = null
                    val pageCount = renderer.pageCount
                    val fileSizeBytes = file.length()

                    val coverBytes: ByteArray? =
                        if (pageCount > 0) {
                            try {
                                renderer.openPage(0).use { page ->
                                    // Fill with white bg — PdfRenderer leaves transparent pixels
                                    // which become black when saved as JPEG (no alpha support)
                                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    ByteArrayOutputStream()
                                        .use { stream ->
                                            bitmap.compress(CompressFormat.JPEG, 80, stream)
                                            stream.toByteArray()
                                        }.also { bitmap.recycle() }
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }

                    PdfMetadata(
                        title = title,
                        author = author,
                        pageCount = pageCount,
                        fileSizeBytes = fileSizeBytes,
                        coverBytes = coverBytes,
                    )
                } finally {
                    renderer.close()
                    fileDescriptor.close()
                }
            }
        }

    override fun getPageCount(file: File): Int =
        try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val count = renderer.pageCount
            renderer.close()
            fileDescriptor.close()
            count
        } catch (e: Exception) {
            0
        }
}

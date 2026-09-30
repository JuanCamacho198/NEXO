package com.nexo.di.modules

import android.content.Context
import coil3.ImageLoader
import com.nexo.data.epub.ZipEpubParserService
import com.nexo.data.pdf.DefaultPdfParserService
import com.nexo.data.storage.AppInternalCoverStorage
import com.nexo.presentation.theme.CoilModule

class StorageModule(
    context: Context,
    @Suppress("UNUSED_PARAMETER") databaseModule: DatabaseModule,
) {
    val coverStorage: AppInternalCoverStorage = AppInternalCoverStorage(context.applicationContext)

    val coilImageLoader: ImageLoader = CoilModule.imageLoader(context.applicationContext)

    val pdfParserService: DefaultPdfParserService = DefaultPdfParserService(context.applicationContext)

    val epubParserService: ZipEpubParserService = ZipEpubParserService()
}

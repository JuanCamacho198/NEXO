package com.nexo.di.modules

import android.content.Context
import coil3.ImageLoader
import com.nexo.data.epub.ZipEpubParserService
import com.nexo.data.pdf.DefaultPdfParserService
import com.nexo.data.storage.AppInternalCoverStorage
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.theme.CoilModule

class StorageModule(
    context: Context,
    @Suppress("UNUSED_PARAMETER") databaseModule: DatabaseModule,
    activeUserProvider: ActiveUserProvider,
) {
    val coverStorage: AppInternalCoverStorage = AppInternalCoverStorage(context.applicationContext, activeUserProvider)

    val coilImageLoader: ImageLoader = CoilModule.imageLoader(context.applicationContext)

    val pdfParserService: DefaultPdfParserService = DefaultPdfParserService(context.applicationContext)

    val epubParserService: ZipEpubParserService = ZipEpubParserService()
}

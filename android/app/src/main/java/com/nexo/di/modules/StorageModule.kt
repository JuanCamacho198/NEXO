package com.nexo.di.modules

import android.content.Context
import coil3.ImageLoader
import com.nexo.data.epub.EpubParserService
import com.nexo.data.epub.ZipEpubParserService
import com.nexo.data.pdf.DefaultPdfParserService
import com.nexo.data.pdf.PdfParserService
import com.nexo.data.storage.AppInternalCoverStorage
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.ui.theme.CoilModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideCoverStorage(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): AppInternalCoverStorage = AppInternalCoverStorage(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideCoverStorageContract(
        coverStorage: AppInternalCoverStorage,
    ): CoverStorage = coverStorage

    @Provides
    @Singleton
    fun provideCoilImageLoader(
        @ApplicationContext context: Context,
    ): ImageLoader = CoilModule.imageLoader(context)

    @Provides
    @Singleton
    fun providePdfParserService(
        @ApplicationContext context: Context,
    ): PdfParserService = DefaultPdfParserService(context)

    @Provides
    @Singleton
    fun provideEpubParserService(): EpubParserService = ZipEpubParserService()
}

package com.nexo.presentation.theme

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object CoilModule {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val MEMORY_CACHE_SIZE_PERCENT = 0.25
    private const val DISK_CACHE_SIZE_BYTES = 64L * 1024L * 1024L

    fun imageLoader(context: Context): ImageLoader {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        return ImageLoader
            .Builder(context)
            // Coil 3 removed `ImageLoader.Builder.okHttpClient {}` and unbundled
            // networking from `coil-core`; the OkHttp engine is registered
            // explicitly through the component registry (mandatory — without it
            // every remote cover blanks).
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, MEMORY_CACHE_SIZE_PERCENT)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("coil-images"))
                    .maxSizeBytes(DISK_CACHE_SIZE_BYTES)
                    .build()
            }.crossfade(true)
            .build()
    }
}

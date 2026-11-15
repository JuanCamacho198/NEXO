package com.nexo.di

import android.content.Context
import com.nexo.data.connectivity.AndroidConnectivityObserver
import com.nexo.domain.connectivity.ConnectivityObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * SDD android-tooling-hygiene WS2a slice 4: FIRST Hilt module (foundation).
 *
 * App-scoped bindings live here as `@Module @InstallIn(SingletonComponent)`.
 * Bindings come from this module and [com.nexo.di.modules.NetworkModule], which
 * single-source construction through [createConnectivityObserver].
 */
@Module
@InstallIn(SingletonComponent::class)
object HiltFoundationModule {
    @Provides
    @Singleton
    fun provideConnectivityObserver(
        @ApplicationContext context: Context,
    ): ConnectivityObserver = createConnectivityObserver(context)
}

/**
 * Shared factory for the app-lifetime [ConnectivityObserver] singleton.
 *
 * Used by [HiltFoundationModule.provideConnectivityObserver], so the observer
 * is constructed from one input by one factory.
 * Network callbacks are process-global; the singleton has no cleanup hook.
 */
fun createConnectivityObserver(context: Context): ConnectivityObserver = AndroidConnectivityObserver(context)

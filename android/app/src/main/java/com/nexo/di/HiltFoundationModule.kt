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
 * This slice migrates exactly one binding — [ConnectivityObserver] — while the
 * manual [AppContainer] keeps constructing every entry-point graph. Both sides
 * single-source construction through [createConnectivityObserver], so the
 * manual container DELEGATES to the same factory the Hilt graph uses (no
 * duplication, no behavior change). Slices 5-6 migrate the remaining bindings
 * and consumers; the container is NOT deleted in this slice.
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
 * Used by [HiltFoundationModule.provideConnectivityObserver] (Hilt graph) and
 * by `NetworkModule.connectivityObserver` (manual [AppContainer] graph), so
 * both graphs construct the identical instance type from the identical input.
 * Network callbacks are process-global; the singleton has no cleanup hook.
 */
fun createConnectivityObserver(context: Context): ConnectivityObserver = AndroidConnectivityObserver(context)

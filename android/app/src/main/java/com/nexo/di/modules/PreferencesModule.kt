package com.nexo.di.modules

import android.content.Context
import com.nexo.data.session.DefinitionPreferences
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.TranslationPreferences
import com.nexo.data.session.UpdatePrefs
import com.nexo.domain.session.ActiveUserProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides
    @Singleton
    fun provideReaderPreferences(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): ReaderPreferences = ReaderPreferences(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideReadingGoalPreferences(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): ReadingGoalPreferences = ReadingGoalPreferences(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideTranslationPreferences(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): TranslationPreferences = TranslationPreferences(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideDefinitionPreferences(
        @ApplicationContext context: Context,
        activeUserProvider: ActiveUserProvider,
    ): DefinitionPreferences = DefinitionPreferences(context, activeUserProvider)

    @Provides
    @Singleton
    fun provideUpdatePrefs(
        @ApplicationContext context: Context,
    ): UpdatePrefs = UpdatePrefs(context)
}

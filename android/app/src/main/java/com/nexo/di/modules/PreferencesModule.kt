package com.nexo.di.modules

import android.content.Context
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.UpdatePrefs
import com.nexo.domain.session.ActiveUserProvider

class PreferencesModule(
    context: Context,
    private val activeUserProvider: ActiveUserProvider,
) {
    val readerPreferences: ReaderPreferences =
        ReaderPreferences(context.applicationContext, activeUserProvider)

    val readingGoalPreferences: ReadingGoalPreferences =
        ReadingGoalPreferences(context.applicationContext, activeUserProvider)

    val updatePrefs: UpdatePrefs = UpdatePrefs(context.applicationContext)

    val dailyGoalProvider: () -> Int = { readingGoalPreferences.load() ?: 30 }
}

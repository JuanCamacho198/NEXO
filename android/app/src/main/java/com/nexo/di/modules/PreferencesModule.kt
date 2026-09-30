package com.nexo.di.modules

import android.content.Context
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.UpdatePrefs

class PreferencesModule(
    context: Context,
) {
    val readerPreferences: ReaderPreferences = ReaderPreferences(context.applicationContext)

    val readingGoalPreferences: ReadingGoalPreferences = ReadingGoalPreferences(context.applicationContext)

    val updatePrefs: UpdatePrefs = UpdatePrefs(context.applicationContext)

    val dailyGoalProvider: () -> Int = { readingGoalPreferences.load() ?: 30 }
}

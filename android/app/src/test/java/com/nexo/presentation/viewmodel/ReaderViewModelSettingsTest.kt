package com.nexo.presentation.viewmodel

import android.app.Application
import com.nexo.data.session.ReaderPreferences
import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSettingsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `default settings are applied on init`() =
        runTest {
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = null,
                )

            val settings = viewModel.settingsUiState.value.readerSettings
            assertEquals(FontSizePreset.M, settings.fontSize)
            assertEquals(ReaderTheme.DARK, settings.theme)
            assertEquals(LineHeightPreset.NORMAL, settings.lineHeight)
        }

    @Test
    fun `settings update lands on the slice flow`() =
        runTest {
            val mockPrefs = mockk<ReaderPreferences>(relaxed = true)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = mockPrefs,
                )

            val newSettings =
                ReaderSettings(
                    fontSize = FontSizePreset.XL,
                    theme = ReaderTheme.SEPIA,
                    lineHeight = LineHeightPreset.COMFORTABLE,
                )

            viewModel.settingsManager.updateReaderSettings(newSettings)

            val slice = viewModel.settingsUiState.value
            assertEquals(FontSizePreset.XL, slice.readerSettings.fontSize)
            assertEquals(ReaderTheme.SEPIA, slice.readerSettings.theme)
            assertEquals(LineHeightPreset.COMFORTABLE, slice.readerSettings.lineHeight)
        }

    @Test
    fun `split-settings toggle flips the slice flag`() =
        runTest {
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = null,
                )

            viewModel.settingsManager.onToggleSplitSettings()

            assertEquals(true, viewModel.settingsUiState.value.showSplitSettings)
        }

    @Test
    fun `settings pass-through delegates are deleted`() {
        val names = ReaderViewModel::class.java.methods.map { it.name }
        assertEquals(false, names.contains("onToggleSplitSettings"))
        assertEquals(false, names.contains("updateReaderSettings"))
        assertEquals(false, names.contains("onUpdateCustomHighlightColor"))
        assertEquals(false, names.contains("onResetCustomHighlightColors"))
    }

    @Test
    fun `updateReaderSettings persists via ReaderPreferences`() =
        runTest {
            val mockPrefs = mockk<ReaderPreferences>(relaxed = true)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = mockPrefs,
                )

            val newSettings =
                ReaderSettings(
                    fontSize = FontSizePreset.XXL,
                    theme = ReaderTheme.LIGHT,
                    lineHeight = LineHeightPreset.WIDE,
                )

            viewModel.settingsManager.updateReaderSettings(newSettings)

            // Verify persistence was called with correct settings
            verify { mockPrefs.save(newSettings) }
        }

    @Test
    fun `settings are loaded from preferences on ViewModel init`() =
        runTest {
            val persistedSettings =
                ReaderSettings(
                    fontSize = FontSizePreset.SM,
                    theme = ReaderTheme.LIGHT,
                    lineHeight = LineHeightPreset.TIGHT,
                )
            val mockPrefs = mockk<ReaderPreferences>(relaxed = true)
            every { mockPrefs.load() } returns persistedSettings

            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = mockPrefs,
                )

            val settings = viewModel.settingsUiState.value.readerSettings
            assertEquals(FontSizePreset.SM, settings.fontSize)
            assertEquals(ReaderTheme.LIGHT, settings.theme)
            assertEquals(LineHeightPreset.TIGHT, settings.lineHeight)
        }

    @Test
    fun `updateReaderSettings with partial change preserves other settings`() =
        runTest {
            val mockPrefs = mockk<ReaderPreferences>(relaxed = true)
            val viewModel =
                ReaderViewModel(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = FakeReaderRepository(),
                    readingStatsRepository = FakeReadingStatsRepository(),
                    updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
                    defaultBookId = null,
                    readerPreferences = mockPrefs,
                )

            // Change only font size — copy with defaults for the rest
            viewModel.settingsManager.updateReaderSettings(
                ReaderSettings(
                    fontSize = FontSizePreset.XXL,
                    theme = ReaderTheme.DARK,
                    lineHeight = LineHeightPreset.NORMAL,
                ),
            )

            val slice = viewModel.settingsUiState.value
            assertEquals(FontSizePreset.XXL, slice.readerSettings.fontSize)
            assertEquals(ReaderTheme.DARK, slice.readerSettings.theme)
            assertEquals(LineHeightPreset.NORMAL, slice.readerSettings.lineHeight)
        }

    private class FakeReaderRepository : ReaderRepository {
        override fun observeProgress(bookId: String): Flow<ReadingProgress?> = MutableStateFlow(null)

        override suspend fun upsertProgress(progress: ReadingProgress) = Unit

        override suspend fun updateBookReadingState(
            bookId: String,
            progressPercent: Float,
            updatedAt: Long,
        ) = Unit

        override fun observeAllHighlights(): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override fun observeHighlights(bookId: String): Flow<List<Highlight>> = MutableStateFlow(emptyList())

        override suspend fun upsertHighlight(highlight: Highlight) = Unit

        override fun observeAllBookmarks(): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

        override suspend fun upsertBookmark(bookmark: Bookmark) = Unit

        override suspend fun getProgressForBook(bookId: String): com.nexo.domain.model.ReadingProgress? = null

        override suspend fun getHighlightsForBook(bookId: String): List<com.nexo.domain.model.Highlight> = emptyList()

        override suspend fun getBookmarksForBook(bookId: String): List<com.nexo.domain.model.Bookmark> = emptyList()
    }

    private class FakeReadingStatsRepository : ReadingStatsRepository {
        override fun observeStats(bookId: String): Flow<ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(): Flow<Long> = MutableStateFlow(0L)

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit

        override fun observeBookStats(): kotlinx.coroutines.flow.Flow<List<com.nexo.domain.repository.ReadingStatsData>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()
    }
}

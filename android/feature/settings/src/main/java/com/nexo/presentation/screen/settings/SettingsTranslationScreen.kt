package com.nexo.presentation.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.data.session.TranslationPreferences
import com.nexo.domain.model.TranslationHistoryEntry
import com.nexo.domain.repository.TranslationHistoryRepository
import com.nexo.domain.translation.TranslationSettings
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsNavRow
import com.nexo.ui.components.molecules.NexoSettingsOptionRow
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Translation languages (source + target) and the translation history.
 *
 * ## What this is NOT
 *
 * [SettingsLanguageScreen] is the UI language and stays global. This screen is
 * the LANGUAGE OF TRANSLATED TEXT and is per-account, persisted by
 * [TranslationPreferences] in `nexo_translation_prefs__{userId}`. The two must
 * not be conflated: an account change may switch which language the reader
 * translates into without moving the app's own language.
 *
 * ## Failing case this closes
 *
 * With no choice available, the target was always the system locale. A book in
 * English on an English phone resolved to `en|en`, which MyMemory echoes back,
 * and the reader got "No translation available" for a perfectly translatable
 * word. Picking a target here is the escape hatch.
 *
 * @param preferencesStore Account-scoped language store; `null` degrades to
 *   in-memory defaults so a preview or a partially-wired graph still renders.
 * @param historyRepository Backed by the translation cache. `null` hides the
 *   history section rather than showing a spinner that never resolves.
 * @param onBack Invoked by the toolbar back arrow.
 */
@Composable
fun SettingsTranslationScreen(
    onBack: () -> Unit,
    preferencesStore: TranslationPreferences? = null,
    historyRepository: TranslationHistoryRepository? = null,
) {
    var settings by remember { mutableStateOf(preferencesStore?.load() ?: TranslationSettings()) }

    fun update(next: TranslationSettings) {
        settings = next
        preferencesStore?.save(next)
    }

    val sameLanguage =
        settings.targetLanguage != null &&
            settings.sourceLanguage != null &&
            settings.targetLanguage == settings.sourceLanguage

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_translation_title),
        onBack = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            LanguagePicker(
                label = stringResource(R.string.settings_translation_target_label),
                options = TARGET_OPTIONS,
                selected = settings.targetLanguage,
                onSelect = { update(settings.copy(targetLanguage = it)) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            LanguagePicker(
                label = stringResource(R.string.settings_translation_source_label),
                options = SOURCE_OPTIONS,
                selected = settings.sourceLanguage,
                onSelect = { update(settings.copy(sourceLanguage = it)) },
            )

            AnimatedVisibility(visible = sameLanguage) {
                Text(
                    text = stringResource(R.string.settings_translation_same_language_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (historyRepository != null) {
                TranslationHistorySection(historyRepository)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private data class TranslationLanguageOption(
    /** `null` = "auto" for source, "follow the device" for target. */
    val code: String?,
    val labelRes: Int,
)

private val TARGET_OPTIONS =
    listOf(
        TranslationLanguageOption(null, R.string.settings_translation_language_system),
        TranslationLanguageOption("es", R.string.settings_translation_spanish),
        TranslationLanguageOption("en", R.string.settings_translation_english),
        TranslationLanguageOption("fr", R.string.settings_translation_french),
        TranslationLanguageOption("de", R.string.settings_translation_german),
        TranslationLanguageOption("it", R.string.settings_translation_italian),
        TranslationLanguageOption("pt", R.string.settings_translation_portuguese),
    )

private val SOURCE_OPTIONS =
    listOf(
        TranslationLanguageOption(null, R.string.settings_translation_language_auto),
        TranslationLanguageOption("es", R.string.settings_translation_spanish),
        TranslationLanguageOption("en", R.string.settings_translation_english),
        TranslationLanguageOption("fr", R.string.settings_translation_french),
        TranslationLanguageOption("de", R.string.settings_translation_german),
        TranslationLanguageOption("it", R.string.settings_translation_italian),
        TranslationLanguageOption("pt", R.string.settings_translation_portuguese),
    )

@Composable
private fun LanguagePicker(
    label: String,
    options: List<TranslationLanguageOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    NexoSettingsSection(title = label) {
        options.forEachIndexed { index, option ->
            if (index > 0) NexoSettingsDivider(inset = false)
            NexoSettingsOptionRow(
                label = stringResource(option.labelRes),
                selected = selected == option.code,
                onClick = { onSelect(option.code) },
            )
        }
    }
}

@Composable
private fun TranslationHistorySection(historyRepository: TranslationHistoryRepository) {
    val entries by historyRepository.history().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val colors = nexoColors

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_translation_history_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { scope.launch { historyRepository.clearHistory() } }) {
                    Text(
                        text = stringResource(R.string.settings_translation_history_clear),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        NexoSettingsSection {
            if (entries.isEmpty()) {
                NexoSettingsNavRow(
                    label = stringResource(R.string.settings_translation_history_empty),
                    icon = NexoIcons.History,
                    accent = colors.textSecondary,
                    onClick = null,
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) NexoSettingsDivider(inset = false)
                    HistoryRow(entry)
                }
            }
        }
    }
}

/** One translation: original, its translation in `primary`, then pair and date. */
@Composable
private fun HistoryRow(entry: TranslationHistoryEntry) {
    val colors = nexoColors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = entry.original,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
        Text(
            text = entry.translated,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.langPair,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            )
            Text(
                text = formatHistoryDate(entry.createdAtEpochMillis),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * `createdAtEpochMillis` is a wall-clock instant, not a reading-day key, so it
 * is rendered in the device time zone (the default) rather than UTC — unlike the
 * weekly-activity weekday labels in `StatisticsScreen`.
 */
private fun formatHistoryDate(epochMillis: Long): String = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(epochMillis))

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsTranslationScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsTranslationScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsTranslationScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsTranslationScreen(onBack = {})
    }
}

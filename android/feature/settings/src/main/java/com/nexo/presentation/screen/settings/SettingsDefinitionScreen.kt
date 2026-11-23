package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.nexo.data.session.DefinitionPreferences
import com.nexo.domain.model.DefinitionHistoryEntry
import com.nexo.domain.repository.DefinitionHistoryRepository
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.DEFINITION_LANGUAGE_OPTIONS
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
 * The reader's DEFINITION language: the language definitions are shown in.
 *
 * ## What this is NOT
 *
 * [SettingsLanguageScreen] is the UI language and stays global.
 * [SettingsTranslationScreen] owns the language of TRANSLATED TEXT. This screen
 * owns the language of DEFINITIONS, stored per account by
 * [DefinitionPreferences] in `nexo_definition_prefs__{userId}`. The three are
 * different preferences and must not be conflated.
 *
 * ## The word's language is not a setting
 *
 * The language of the WORD being defined comes from the book, never from here;
 * the definition panel shows it as read-only text. Only the definition language
 * is a preference.
 *
 * ## One store, two views
 *
 * The definition panel's selector (T8) reads and writes the SAME
 * [DefinitionPreferences], so this screen and the panel can never show different
 * values. A `null` selection means "follow the app UI locale".
 *
 * ## History
 *
 * The words this account looked up, newest first, read from the definition cache.
 * Clearing the list also empties the offline definitions, so it asks first.
 *
 * @param preferencesStore Account-scoped definition-language store; `null`
 *   degrades to in-memory defaults so a preview or a partially-wired graph still
 *   renders.
 * @param historyRepository Backed by the definition cache. `null` hides the
 *   history section rather than showing a spinner that never resolves.
 * @param onBack Invoked by the toolbar back arrow.
 */
@Composable
fun SettingsDefinitionScreen(
    onBack: () -> Unit,
    preferencesStore: DefinitionPreferences? = null,
    languageLabels: Map<String, String> = emptyMap(),
    historyRepository: DefinitionHistoryRepository? = null,
) {
    var selected by remember { mutableStateOf(preferencesStore?.load()) }

    fun update(language: String?) {
        selected = language
        preferencesStore?.save(language)
    }

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_definition_title),
        onBack = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            NexoSettingsSection(
                title = stringResource(R.string.settings_definition_language_label),
                footer = stringResource(R.string.settings_definition_language_hint),
            ) {
                NexoSettingsOptionRow(
                    label = stringResource(R.string.settings_translation_language_system),
                    icon = NexoIcons.Smartphone,
                    selected = selected == null,
                    onClick = { update(null) },
                )
                DEFINITION_LANGUAGE_OPTIONS.forEach { code ->
                    NexoSettingsDivider()
                    NexoSettingsOptionRow(
                        label = languageLabels[code] ?: code,
                        icon = NexoIcons.BookSearch,
                        accent = nexoColors.accentOrange,
                        selected = selected == code,
                        onClick = { update(code) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (historyRepository != null) {
                DefinitionHistorySection(historyRepository)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The words this account looked up, newest first.
 *
 * Reads the definition cache (see
 * [com.nexo.data.repository.RoomDefinitionHistoryRepository]), so clearing here
 * ALSO empties the offline definitions — the confirmation says so instead of
 * letting the reader discover it the next time they are offline.
 */
@Composable
private fun DefinitionHistorySection(historyRepository: DefinitionHistoryRepository) {
    val entries by historyRepository.history().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val colors = nexoColors
    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_definition_history_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmingClear = true }) {
                    Text(
                        text = stringResource(R.string.settings_definition_history_clear),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        NexoSettingsSection {
            if (entries.isEmpty()) {
                NexoSettingsNavRow(
                    label = stringResource(R.string.settings_definition_history_empty),
                    icon = NexoIcons.History,
                    accent = colors.textSecondary,
                    onClick = null,
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) NexoSettingsDivider(inset = false)
                    DefinitionHistoryRow(entry)
                }
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.settings_definition_history_clear_title)) },
            text = { Text(stringResource(R.string.settings_definition_history_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        scope.launch { historyRepository.clearHistory() }
                    },
                ) {
                    Text(stringResource(R.string.settings_definition_history_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.settings_definition_history_clear_cancel))
                }
            },
        )
    }
}

/** One definition: the word, its gloss in `primary`, then pair and date. */
@Composable
private fun DefinitionHistoryRow(entry: DefinitionHistoryEntry) {
    val colors = nexoColors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = entry.word,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
        Text(
            text = entry.definition,
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
                text = historyLanguagePair(entry),
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

/** `en → es`, plus the part of speech when the source gave one. */
private fun historyLanguagePair(entry: DefinitionHistoryEntry): String {
    val pair = "${entry.wordLanguage} → ${entry.readerLanguage}"
    return entry.partOfSpeech?.takeIf { it.isNotBlank() }?.let { "$pair · $it" } ?: pair
}

/**
 * `createdAtEpochMillis` is a wall-clock instant, so it is rendered in the
 * device time zone (the default) rather than UTC — same rule as the translation
 * history rows.
 */
private fun formatHistoryDate(epochMillis: Long): String = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(epochMillis))

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsDefinitionScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsDefinitionScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsDefinitionScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsDefinitionScreen(onBack = {})
    }
}

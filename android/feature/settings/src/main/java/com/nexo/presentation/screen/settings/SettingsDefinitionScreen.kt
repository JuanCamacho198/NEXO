package com.nexo.presentation.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.data.session.DefinitionPreferences
import com.nexo.domain.model.DefinitionHistoryEntry
import com.nexo.domain.repository.DefinitionHistoryRepository
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.DEFINITION_LANGUAGE_OPTIONS
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
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
            Text(
                text = stringResource(R.string.settings_definition_language_label),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            DefinitionLanguageOption(
                code = null,
                label = stringResource(R.string.settings_translation_language_system),
                isSelected = selected == null,
                onSelect = ::update,
            )

            Spacer(modifier = Modifier.height(8.dp))

            DEFINITION_LANGUAGE_OPTIONS.forEach { code ->
                DefinitionLanguageOption(
                    code = code,
                    label = languageLabels[code] ?: code,
                    isSelected = selected == code,
                    onSelect = ::update,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_definition_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_definition_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmingClear = true }) {
                    Text(stringResource(R.string.settings_definition_history_clear))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_definition_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
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

@Composable
private fun DefinitionHistoryRow(entry: DefinitionHistoryEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = entry.word,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.definition,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = historyLanguagePair(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatHistoryDate(entry.createdAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun DefinitionLanguageOption(
    code: String?,
    label: String,
    isSelected: Boolean,
    onSelect: (String?) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(code) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (isSelected) {
                Icon(
                    imageVector = NexoIcons.Check,
                    contentDescription = stringResource(R.string.a11y_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

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

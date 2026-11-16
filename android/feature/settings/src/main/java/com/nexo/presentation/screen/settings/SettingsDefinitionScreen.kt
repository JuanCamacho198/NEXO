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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.data.session.DefinitionPreferences
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.DEFINITION_LANGUAGE_OPTIONS
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

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
 * @param onBack Invoked by the toolbar back arrow.
 */
@Composable
fun SettingsDefinitionScreen(
    onBack: () -> Unit,
    preferencesStore: DefinitionPreferences? = null,
    languageLabels: Map<String, String> = emptyMap(),
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
        }
    }
}

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
                    contentDescription = null,
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

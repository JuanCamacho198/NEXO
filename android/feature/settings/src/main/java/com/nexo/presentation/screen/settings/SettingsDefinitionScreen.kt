package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.data.session.DefinitionPreferences
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.DEFINITION_LANGUAGE_OPTIONS
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsOptionRow
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

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

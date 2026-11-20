package com.nexo.presentation.screen.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.os.LocaleListCompat
import com.nexo.data.session.AppLanguagePreferences
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsOptionRow
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

private data class LanguageOption(
    val code: String?,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun SettingsLanguageScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val langPrefs = AppLanguagePreferences(context = context)
    val currentCode = langPrefs.load()

    val options =
        listOf(
            LanguageOption("es", R.string.settings_language_spanish, NexoIcons.Translate),
            LanguageOption("en", R.string.settings_language_english, NexoIcons.Translate),
            LanguageOption(null, R.string.settings_language_system, NexoIcons.Smartphone),
        )

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_language_title),
        onBack = onBack,
    ) {
        NexoSettingsSection {
            options.forEachIndexed { index, option ->
                if (index > 0) NexoSettingsDivider()
                NexoSettingsOptionRow(
                    label = stringResource(option.labelRes),
                    icon = option.icon,
                    selected = currentCode == option.code,
                    onClick = {
                        langPrefs.save(option.code)
                        if (option.code != null) {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(option.code),
                            )
                        } else {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.getEmptyLocaleList(),
                            )
                        }
                    },
                )
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsLanguageScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsLanguageScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsLanguageScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsLanguageScreen(onBack = {})
    }
}

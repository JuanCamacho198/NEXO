package com.nexo.presentation.screen.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.nexo.R
import com.nexo.data.session.AppLanguagePreferences
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons

private data class LanguageOption(
    val code: String?,
    val labelRes: Int,
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
            LanguageOption("es", R.string.settings_language_spanish),
            LanguageOption("en", R.string.settings_language_english),
            LanguageOption(null, R.string.settings_language_system),
        )

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_language_title),
        onBack = onBack,
    ) {
        options.forEach { option ->
            val selected = currentCode == option.code
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
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
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (selected) {
                        Icon(
                            imageVector = NexoIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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

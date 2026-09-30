package com.nexo.presentation.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.ThemeMode
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons

private data class ThemeOption(
    val mode: ThemeMode,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun SettingsThemeScreen(
    appThemeMode: ThemeMode,
    onAppThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val options =
        listOf(
            ThemeOption(ThemeMode.LIGHT, R.string.settings_theme_light, NexoIcons.LightMode),
            ThemeOption(ThemeMode.DARK, R.string.settings_theme_dark, NexoIcons.DarkMode),
            ThemeOption(ThemeMode.SYSTEM, R.string.settings_theme_system, NexoIcons.BrightnessAuto),
        )

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_theme_title),
        onBack = onBack,
    ) {
        options.forEach { option ->
            val selected = appThemeMode == option.mode
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAppThemeModeChanged(option.mode) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
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
private fun SettingsThemeScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsThemeScreen(
            appThemeMode = ThemeMode.SYSTEM,
            onAppThemeModeChanged = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsThemeScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsThemeScreen(
            appThemeMode = ThemeMode.SYSTEM,
            onAppThemeModeChanged = {},
            onBack = {},
        )
    }
}

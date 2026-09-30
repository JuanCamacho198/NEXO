package com.nexo.presentation.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.presentation.theme.NexoTheme

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
) {
    AboutScreen(onBack = onBack)
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsAboutScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsAboutScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsAboutScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsAboutScreen(onBack = {})
    }
}

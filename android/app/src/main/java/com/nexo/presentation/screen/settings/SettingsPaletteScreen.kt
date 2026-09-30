package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.HighlightPaletteSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage

@Composable
fun SettingsPaletteScreen(
    customHighlightColors: List<String>?,
    onUpdateCustomHighlightColor: (Int, String) -> Unit,
    onAddCustomHighlightColor: () -> Unit,
    onDeleteCustomHighlightColor: (Int) -> Unit,
    onResetCustomHighlightColors: () -> Unit,
    onBack: () -> Unit,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.palette_section_title),
        onBack = onBack,
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        HighlightPaletteSection(
            customColors = customHighlightColors,
            onUpdateColor = { index, hex ->
                onUpdateCustomHighlightColor(index, hex)
            },
            onAddColor = onAddCustomHighlightColor,
            onDeleteColor = onDeleteCustomHighlightColor,
            onReset = onResetCustomHighlightColors,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsPaletteScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsPaletteScreen(
            customHighlightColors = emptyList(),
            onUpdateCustomHighlightColor = { _, _ -> },
            onAddCustomHighlightColor = {},
            onDeleteCustomHighlightColor = {},
            onResetCustomHighlightColors = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPaletteScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsPaletteScreen(
            customHighlightColors = emptyList(),
            onUpdateCustomHighlightColor = { _, _ -> },
            onAddCustomHighlightColor = {},
            onDeleteCustomHighlightColor = {},
            onResetCustomHighlightColors = {},
            onBack = {},
        )
    }
}

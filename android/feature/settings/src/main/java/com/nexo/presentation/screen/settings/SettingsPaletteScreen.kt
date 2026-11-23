package com.nexo.presentation.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.HighlightPaletteSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.theme.NexoTheme

@Composable
fun SettingsPaletteScreen(
    customHighlightColors: List<String>?,
    onUpdateCustomHighlightColor: (Int, String) -> Unit,
    onAddCustomHighlightColor: () -> Unit,
    onDeleteCustomHighlightColor: (Int) -> Unit,
    onResetCustomHighlightColors: () -> Unit,
    onBack: () -> Unit,
    // The palette copy lives in :app's resources, so the host resolves it.
    // Without it every label rendered empty (a blank red "reset" row, an
    // untitled colour dialog).
    subtitle: String? = null,
    addColorLabel: String? = null,
    resetLabel: String? = null,
    pickColorLabel: String? = null,
) {
    NexoSettingsSubPage(
        title = stringResource(R.string.palette_section_title),
        onBack = onBack,
    ) {
        HighlightPaletteSection(
            subtitle = subtitle,
            addColorLabel = addColorLabel,
            resetLabel = resetLabel,
            pickColorLabel = pickColorLabel,
            cancelLabel = stringResource(R.string.cancel),
            deleteContentDescription = stringResource(R.string.storage_action_delete),
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

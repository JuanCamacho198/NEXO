package com.nexo.presentation.screen.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.ThemeMode
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEach { option ->
                ThemeTile(
                    option = option,
                    selected = appThemeMode == option.mode,
                    onClick = { onAppThemeModeChanged(option.mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val TileShape = RoundedCornerShape(18.dp)
private val PreviewShape = RoundedCornerShape(12.dp)

/** Fixed preview swatches: a tile shows ITS theme, whatever the app's current one is. */
private val LightPage = Color(0xFFF7F8FA)
private val LightInk = Color(0xFFCDD3DD)
private val DarkPage = Color(0xFF0B1120)
private val DarkInk = Color(0xFF2A3550)

/**
 * A theme as a picture: a miniature page in that theme above its label, instead
 * of a plain text row. The selected tile gets a `primary` ring; "system" is drawn
 * half light, half dark.
 */
@Composable
private fun ThemeTile(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val ring by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.borderSubtle,
        animationSpec = tween(200),
        label = "themeRing",
    )
    Column(
        modifier =
            modifier
                .clip(TileShape)
                .background(colors.surface)
                .border(if (selected) 2.dp else 1.dp, ring, TileShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(PreviewShape),
        ) {
            when (option.mode) {
                ThemeMode.LIGHT -> MiniPage(page = LightPage, ink = LightInk, accent = colors.primary)
                ThemeMode.DARK -> MiniPage(page = DarkPage, ink = DarkInk, accent = colors.primary)
                ThemeMode.SYSTEM ->
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) { MiniPage(page = LightPage, ink = LightInk, accent = colors.primary) }
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) { MiniPage(page = DarkPage, ink = DarkInk, accent = colors.primary) }
                    }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = if (selected) colors.primary else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(option.labelRes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.primary else colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** A tiny page: a title bar, three lines of text and an accent pill. */
@Composable
private fun MiniPage(
    page: Color,
    ink: Color,
    accent: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(page)
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(0.55f).height(6.dp).clip(CircleShape).background(ink))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
        Box(modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
        Box(modifier = Modifier.fillMaxWidth(0.9f).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
        Box(modifier = Modifier.fillMaxWidth(0.45f).height(8.dp).clip(CircleShape).background(accent))
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

package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme

@Composable
fun ThemeCirclesSection(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.reader_settings_theme_section),
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF718096),
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ThemeCircle(
                color = "#FFFFFF",
                label = stringResource(R.string.reader_theme_light),
                isSelected = settings.theme == ReaderTheme.LIGHT,
                onClick = { onSettingsChanged(settings.copy(theme = ReaderTheme.LIGHT)) },
            )
            ThemeCircle(
                color = "#F4ECD8",
                label = stringResource(R.string.reader_theme_sepia),
                isSelected = settings.theme == ReaderTheme.SEPIA,
                onClick = { onSettingsChanged(settings.copy(theme = ReaderTheme.SEPIA)) },
            )
            ThemeCircle(
                color = "#121212",
                label = stringResource(R.string.reader_theme_dark),
                isSelected = settings.theme == ReaderTheme.DARK,
                onClick = { onSettingsChanged(settings.copy(theme = ReaderTheme.DARK)) },
            )
            ThemeCircle(
                color = "#0D1322",
                label = stringResource(R.string.theme_oled),
                isSelected = settings.theme == ReaderTheme.OLED,
                onClick = { onSettingsChanged(settings.copy(theme = ReaderTheme.OLED)) },
            )
        }
    }
}

@Composable
private fun ThemeCircle(
    color: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(parseColorHex(color))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, Color(0xFFADC6FF), CircleShape)
                        } else {
                            Modifier
                        },
                    ).clickable(onClick = onClick),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) Color(0xFFADC6FF) else Color(0xFF718096),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

private fun parseColorHex(hex: String): Color =
    try {
        val sanitized = hex.removePrefix("#")
        val longHex =
            when (sanitized.length) {
                6 -> "FF$sanitized"
                8 -> sanitized
                else -> "FF000000"
            }
        Color(longHex.toLong(16))
    } catch (_: Exception) {
        Color.Magenta
    }

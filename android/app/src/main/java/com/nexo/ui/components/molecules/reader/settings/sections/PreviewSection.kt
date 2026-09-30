package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.ReaderSettings

@Composable
fun PreviewSection(
    settings: ReaderSettings,
    previewText: String,
    modifier: Modifier = Modifier,
) {
    val previewFontFamily =
        when (settings.fontName) {
            "Arial" -> androidx.compose.ui.text.font.FontFamily.SansSerif
            "Merriweather" -> androidx.compose.ui.text.font.FontFamily.Serif
            else -> androidx.compose.ui.text.font.FontFamily.Default
        }
    val previewAlignment =
        when (settings.layoutPrefs.alignment) {
            LayoutPreferences.Alignment.LEFT -> TextAlign.Left
            LayoutPreferences.Alignment.CENTER -> TextAlign.Center
            LayoutPreferences.Alignment.RIGHT -> TextAlign.Right
            LayoutPreferences.Alignment.JUSTIFY -> TextAlign.Justify
        }

    Box(
        modifier =
            modifier
                .background(parseColorHex(settings.theme.bgHex))
                .padding(
                    start = settings.layoutPrefs.leftMargin.dp,
                    end = settings.layoutPrefs.rightMargin.dp,
                    top = 20.dp,
                    bottom = 20.dp,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = previewText.ifBlank { stringResource(R.string.aa_preview_text) },
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize =
                        when (settings.fontSize) {
                            FontSizePreset.XS -> 12.sp
                            FontSizePreset.S -> 13.sp
                            FontSizePreset.SM -> 14.sp
                            FontSizePreset.M -> 16.sp
                            FontSizePreset.ML -> 18.sp
                            FontSizePreset.L -> 20.sp
                            FontSizePreset.XL -> 22.sp
                            FontSizePreset.XXL -> 26.sp
                        },
                    lineHeight =
                        when (settings.lineHeight) {
                            com.nexo.domain.model.LineHeightPreset.TIGHT -> 18.sp
                            com.nexo.domain.model.LineHeightPreset.NORMAL -> 22.sp
                            com.nexo.domain.model.LineHeightPreset.COMFORTABLE -> 26.sp
                            com.nexo.domain.model.LineHeightPreset.WIDE -> 30.sp
                        },
                    fontFamily = previewFontFamily,
                ),
            color = parseColorHex(settings.theme.textHex),
            textAlign = previewAlignment,
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

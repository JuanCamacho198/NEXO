package com.nexo.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nexo.R

/**
 * Material 3 [Typography] scale for NEXO.
 *
 * Wires into [NexoTheme] via `MaterialTheme.typography`. All styles use the
 * Lato family (4 static instances in `res/font/lato_*.ttf`; OFL 1.1 shipped
 * alongside in `res/raw/lato_ofl.txt`). Missing weights fall back through the
 * system font chain.
 *
 * Scale overview (Material 3 spec):
 * - `displayLarge` (57sp Bold) — hero/splash text, not used in current screens
 * - `headline*` (32/28/24sp) — screen titles, section headers
 * - `title*` (22/16/14sp) — card titles, dialog titles, list-item primaries
 * - `body*` (16/14/12sp) — paragraph text, captions, helper text
 * - `label*` (14/12/11sp) — buttons, chips, tab labels, dense metadata
 */
private val LatoFontFamily =
    FontFamily(
        Font(R.font.lato_regular, FontWeight.Normal),
        Font(R.font.lato_medium, FontWeight.Medium),
        Font(R.font.lato_semibold, FontWeight.SemiBold),
        Font(R.font.lato_bold, FontWeight.Bold),
    )

val NexoTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
    )

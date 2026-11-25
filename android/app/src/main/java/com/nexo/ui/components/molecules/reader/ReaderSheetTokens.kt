package com.nexo.ui.components.molecules.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Palette shared by the reader's sheets (appearance, contents, highlights).
 *
 * The reader surface is always dark regardless of the app theme, so these sheets
 * are locked to it instead of following `nexoColors`. [TextSecondary] is lighter
 * than the legacy `#718096`, which measured 4.1:1 on [Surface] — under the 4.5:1
 * floor for body-size text; this one measures 5.5:1.
 */
internal object ReaderSheetColors {
    val Surface = Color(0xFF161F33)
    val SurfaceRaised = Color(0xFF1E2842)
    val SurfaceSelected = Color(0xFF2C3858)
    val Outline = Color(0xFF2F3A55)
    val Handle = Color(0xFF4A5568)
    val TextPrimary = Color(0xFFDDE2F8)
    val TextSecondary = Color(0xFF8B95AD)
    val Accent = Color(0xFFADC6FF)
}

internal val ReaderSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@Composable
internal fun ReaderSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ReaderSheetColors.Handle),
        )
    }
}

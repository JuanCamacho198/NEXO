package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.ReaderSettings

@Composable
fun FontSizeSection(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "A-",
            color = Color(0xFF718096),
            fontSize = 12.sp,
        )

        Slider(
            value = settings.fontSize.ordinal.toFloat(),
            onValueChange = { value ->
                val preset = FontSizePreset.fromOrdinal(value.toInt())
                onSettingsChanged(settings.copy(fontSize = preset))
            },
            valueRange = 0f..(FontSizePreset.entries.size - 1).toFloat(),
            steps = FontSizePreset.entries.size - 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = Color(0xFFADC6FF),
                    activeTrackColor = Color(0xFFADC6FF),
                    inactiveTrackColor = Color(0xFF2F3445),
                ),
        )

        Text(
            text = "A+",
            color = Color(0xFF718096),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

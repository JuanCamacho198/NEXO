package com.nexo.ui.components.molecules.reader.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.ReaderSettings
import com.nexo.ui.icons.NexoIcons

@Composable
fun FontSelectorSection(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFontDropdown by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2F3445))
                .clickable { showFontDropdown = true }
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = NexoIcons.TextAa,
                contentDescription = null,
                tint = Color(0xFFADC6FF),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = settings.fontName,
                color = Color(0xFFDDE2F8),
                fontSize = 14.sp,
            )
        }
        Icon(
            imageVector = NexoIcons.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF718096),
            modifier = Modifier.size(20.dp),
        )
    }

    DropdownMenu(
        expanded = showFontDropdown,
        onDismissRequest = { showFontDropdown = false },
    ) {
        listOf("Georgia", "Arial", "Merriweather").forEach { font ->
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            when (font) {
                                "Georgia" -> stringResource(R.string.font_name_georgia)
                                "Arial" -> stringResource(R.string.font_name_arial)
                                "Merriweather" -> stringResource(R.string.font_name_merriweather)
                                else -> font
                            },
                    )
                },
                onClick = {
                    onSettingsChanged(settings.copy(fontName = font))
                    showFontDropdown = false
                },
            )
        }
    }
}

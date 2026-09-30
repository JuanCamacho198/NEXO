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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ScrollMode
import com.nexo.ui.icons.NexoIcons

@Composable
fun ExpandablesSection(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showJustificationSection by remember { mutableStateOf(false) }
    var showMarginsSection by remember { mutableStateOf(false) }
    var showDirectionSection by remember { mutableStateOf(false) }
    var showAudioSection by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            label = stringResource(R.string.editor_values),
            checked = settings.editorValues,
            onCheckedChange = { onSettingsChanged(settings.copy(editorValues = it)) },
        )

        Spacer(modifier = Modifier.height(4.dp))

        SettingsSwitchRow(
            label = stringResource(R.string.vertical_scroll),
            checked = settings.verticalScroll,
            onCheckedChange = {
                onSettingsChanged(
                    settings.copy(
                        verticalScroll = it,
                        scrollMode = if (it) ScrollMode.VERTICAL else ScrollMode.PAGINATED,
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        ExpandableSection(
            label = stringResource(R.string.aa_section_justification),
            isExpanded = showJustificationSection,
            onToggle = { showJustificationSection = !showJustificationSection },
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val alignmentOptions =
                        listOf(
                            LayoutPreferences.Alignment.LEFT to NexoIcons.AlignLeft,
                            LayoutPreferences.Alignment.CENTER to NexoIcons.AlignCenter,
                            LayoutPreferences.Alignment.RIGHT to NexoIcons.AlignRight,
                            LayoutPreferences.Alignment.JUSTIFY to NexoIcons.AlignJustify,
                        )
                    alignmentOptions.forEach { (alignment, icon) ->
                        val isSelected = settings.layoutPrefs.alignment == alignment
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF2F3445) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFFADC6FF) else Color(0xFF4A5568),
                                        RoundedCornerShape(8.dp),
                                    ).clickable {
                                        onSettingsChanged(
                                            settings.copy(
                                                layoutPrefs = settings.layoutPrefs.copy(alignment = alignment),
                                            ),
                                        )
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = alignment.name,
                                tint = if (isSelected) Color(0xFFADC6FF) else Color(0xFF718096),
                            )
                        }
                    }
                }
            },
        )

        ExpandableSection(
            label = stringResource(R.string.aa_section_margins),
            isExpanded = showMarginsSection,
            onToggle = { showMarginsSection = !showMarginsSection },
            content = {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.aa_margin_left) + ": ${settings.layoutPrefs.leftMargin}px",
                        color = Color(0xFFDDE2F8),
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = settings.layoutPrefs.leftMargin.toFloat(),
                        onValueChange = { value ->
                            onSettingsChanged(
                                settings.copy(
                                    layoutPrefs = settings.layoutPrefs.copy(leftMargin = value.toInt()),
                                ),
                            )
                        },
                        valueRange = 0f..40f,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = Color(0xFFADC6FF),
                                activeTrackColor = Color(0xFFADC6FF),
                                inactiveTrackColor = Color(0xFF2F3445),
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.aa_margin_right) + ": ${settings.layoutPrefs.rightMargin}px",
                        color = Color(0xFFDDE2F8),
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = settings.layoutPrefs.rightMargin.toFloat(),
                        onValueChange = { value ->
                            onSettingsChanged(
                                settings.copy(
                                    layoutPrefs = settings.layoutPrefs.copy(rightMargin = value.toInt()),
                                ),
                            )
                        },
                        valueRange = 0f..40f,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = Color(0xFFADC6FF),
                                activeTrackColor = Color(0xFFADC6FF),
                                inactiveTrackColor = Color(0xFF2F3445),
                            ),
                    )
                }
            },
        )

        ExpandableSection(
            label = stringResource(R.string.aa_section_direction),
            isExpanded = showDirectionSection,
            onToggle = { showDirectionSection = !showDirectionSection },
            content = {
                Text(
                    text = stringResource(R.string.aa_direction_ltr),
                    color = Color(0xFF718096),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp),
                )
            },
        )

        ExpandableSection(
            label = stringResource(R.string.aa_section_audio),
            isExpanded = showAudioSection,
            onToggle = { showAudioSection = !showAudioSection },
            content = {
                Text(
                    text = stringResource(R.string.aa_audio_coming_soon),
                    color = Color(0xFF718096),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp),
                )
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFDDE2F8),
            fontSize = 14.sp,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFADC6FF),
                    checkedTrackColor = Color(0xFFADC6FF).copy(alpha = 0.3f),
                    uncheckedThumbColor = Color(0xFF4A5568),
                    uncheckedTrackColor = Color(0xFF2F3445),
                ),
        )
    }
}

@Composable
private fun ExpandableSection(
    label: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color(0xFFDDE2F8),
                fontSize = 14.sp,
            )
            Icon(
                imageVector = if (isExpanded) NexoIcons.ArrowLeft else NexoIcons.ChevronRight,
                contentDescription = if (isExpanded) stringResource(R.string.aa_collapse) else stringResource(R.string.aa_expand),
                tint = Color(0xFF718096),
                modifier = Modifier.size(20.dp),
            )
        }

        if (isExpanded) {
            content()
        }

        HorizontalDivider(color = Color(0xFF2F3445))
    }
}

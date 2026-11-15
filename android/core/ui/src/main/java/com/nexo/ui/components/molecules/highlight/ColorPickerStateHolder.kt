package com.nexo.ui.components.molecules.highlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class ColorPickerState(
    initialColor: String,
) {
    var selectedColor by mutableStateOf(initialColor)
    var hexInput by mutableStateOf(initialColor.removePrefix("#"))
    var hue by mutableFloatStateOf(hueFromHex(initialColor))
    var spectrumPosition by mutableFloatStateOf(0.5f)
}

@Composable
fun rememberColorPickerState(initialColor: String): ColorPickerState = remember { ColorPickerState(initialColor) }

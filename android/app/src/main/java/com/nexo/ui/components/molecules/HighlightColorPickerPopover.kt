package com.nexo.ui.components.molecules

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.highlight.ColorPickerState
import com.nexo.ui.components.molecules.highlight.hslToColor
import com.nexo.ui.components.molecules.highlight.hslToHex
import com.nexo.ui.components.molecules.highlight.hueFromHex
import com.nexo.ui.components.molecules.highlight.parseColorHex
import com.nexo.ui.components.molecules.highlight.rememberColorPickerState
import com.nexo.ui.components.molecules.highlight.spectrumColorAt

/** Default Pencil kixeV design color presets (5 hex values). */
val DEFAULT_HIGHLIGHT_PRESETS =
    listOf(
        "#4ADE80",
        "#3B82F6",
        "#F97316",
        "#EF4444",
        "#FACC15",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerContent(
    presets: List<String>,
    state: ColorPickerState,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            presets.take(5).forEach { hex ->
                val isActive = hex.equals(state.selectedColor, ignoreCase = true)
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(parseColorHex(hex))
                            .then(
                                if (isActive) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                } else {
                                    Modifier
                                },
                            ).clickable {
                                state.selectedColor = hex
                                state.hexInput = hex.removePrefix("#")
                                state.hue = hueFromHex(hex)
                                onColorSelected(hex)
                                onDismiss()
                            },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SpectrumBar(
            currentPosition = state.spectrumPosition,
            hue = state.hue,
            onPositionChange = { pos ->
                state.spectrumPosition = pos
                state.selectedColor = spectrumColorAt(pos, state.hue)
                state.hexInput = state.selectedColor.removePrefix("#")
            },
            modifier = Modifier.fillMaxWidth().height(128.dp),
        )

        Spacer(Modifier.height(8.dp))

        HueSlider(
            hue = state.hue,
            onHueChange = { newHue ->
                state.hue = newHue
                state.selectedColor = hslToHex(newHue)
                state.hexInput = state.selectedColor.removePrefix("#")
            },
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.hexInput.uppercase(),
            onValueChange = { input ->
                val clean = input.filter { it in "0123456789ABCDEFabcdef" }.take(6)
                state.hexInput = clean
                if (clean.length == 6) {
                    val hex = "#$clean"
                    state.selectedColor = hex
                    state.hue = hueFromHex(hex)
                }
            },
            textStyle =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            leadingIcon = {
                Text(
                    text = "#",
                    style =
                        TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            },
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.color_picker_confirm),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightColorPickerPopover(
    customColors: List<String>?,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    anchorX: Int = 0,
    anchorY: Int = 0,
    modifier: Modifier = Modifier,
) {
    val presets = (customColors?.takeIf { it.size >= 5 } ?: DEFAULT_HIGHLIGHT_PRESETS).take(5)
    val pickerState = rememberColorPickerState(presets.first())

    Column(
        modifier =
            modifier
                .width(220.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ColorPickerContent(
            presets = presets,
            state = pickerState,
            onColorSelected = onColorSelected,
            onDismiss = onDismiss,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightColorPickerPopoverDarkPreview() {
    NexoTheme(darkTheme = true) {
        HighlightColorPickerPopover(customColors = null, onColorSelected = {}, onDismiss = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightColorPickerPopoverLightPreview() {
    NexoTheme(darkTheme = false) {
        HighlightColorPickerPopover(customColors = null, onColorSelected = {}, onDismiss = {})
    }
}

@Composable
private fun SpectrumBar(
    currentPosition: Float,
    hue: Float,
    onPositionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbRadius = 8.dp
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    // awaitPointerEventScope merging detectTapGestures + drag, keyed pointerInput(Unit)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent().changes.firstOrNull() ?: continue
                                if (!down.pressed) {
                                    awaitPointerEvent()
                                    continue
                                }
                                // tap via detectTapGestures semantics: immediate position update
                                val tapPos = (down.position.x / size.width).coerceIn(0f, 1f)
                                onPositionChange(tapPos)
                                // drag continuation — detectTapGestures + horizontal drag merged
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    val pos = (change.position.x / size.width).coerceIn(0f, 1f)
                                    onPositionChange(pos)
                                    change.consume()
                                }
                            }
                        }
                    },
        ) {
            val saturatedColor = hslToColor(hue, 1f, 0.5f)
            drawRect(
                brush = Brush.horizontalGradient(colors = listOf(Color.Black, saturatedColor, Color.White)),
                size = size,
            )
            val thumbX = currentPosition * size.width
            val thumbCenter = Offset(thumbX, size.height / 2f)
            drawCircle(color = Color.White, radius = thumbRadius.toPx(), center = thumbCenter)
            drawCircle(
                color = Color(0xFF1F2937),
                radius = thumbRadius.toPx(),
                center = thumbCenter,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rainbowColors = remember { List(12) { i -> hslToColor(i * 30f, 1f, 0.5f) } }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(rainbowColors)),
        )
        Slider(
            value = hue,
            onValueChange = onHueChange,
            valueRange = 0f..360f,
            modifier = Modifier.fillMaxWidth(),
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            thumb = {
                Box(
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, Color(0xFFD1D5DB), CircleShape),
                )
            },
        )
    }
}

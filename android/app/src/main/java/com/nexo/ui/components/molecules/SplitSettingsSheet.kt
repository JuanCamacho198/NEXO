package com.nexo.ui.components.molecules

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.ReaderSettings
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.components.molecules.reader.ReaderSheetHandle
import com.nexo.ui.components.molecules.reader.ReaderSheetShape
import com.nexo.ui.components.molecules.reader.settings.sections.AppearanceSectionLabel
import com.nexo.ui.components.molecules.reader.settings.sections.FontChoices
import com.nexo.ui.components.molecules.reader.settings.sections.MarginControls
import com.nexo.ui.components.molecules.reader.settings.sections.ReadingModeChoices
import com.nexo.ui.components.molecules.reader.settings.sections.TextLayoutGroup
import com.nexo.ui.components.molecules.reader.settings.sections.TextSizeStepper
import com.nexo.ui.components.molecules.reader.settings.sections.ThemeSwatches
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Share of the screen the panel may take; the rest stays the live book. */
private const val PANEL_MAX_HEIGHT_FRACTION = 0.56f

/** Drag distance (dp) or fling speed (px/s) past which a downward drag closes the panel. */
private const val DISMISS_DRAG_DP = 96
private const val DISMISS_FLING_PX_PER_S = 1200f

/**
 * Reader appearance panel, split-screen style: the panel docks to the bottom
 * and the real book stays visible above it, so every change is seen on the
 * actual page as it is made (the settings reach the EPUB engine live).
 *
 * The page above is not covered: a tap on it is routed by the reader screen to
 * close the panel, and swipes still turn pages to check the result elsewhere.
 * Closing: "Done", a downward drag on the header, a tap on the page, or back.
 */
@Composable
fun SplitSettingsSheet(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    val dragState =
        rememberDraggableState { delta ->
            scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
        }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * PANEL_MAX_HEIGHT_FRACTION)
                    .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                    .shadow(16.dp, ReaderSheetShape)
                    .clip(ReaderSheetShape)
                    .background(ReaderSheetColors.Surface)
                    // Swallow touches on empty panel areas so they never fall
                    // through to the book underneath.
                    .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                if (dragOffset.value > dismissPx || velocity > DISMISS_FLING_PX_PER_S) {
                                    onDismiss()
                                } else {
                                    dragOffset.animateTo(0f)
                                }
                            },
                        ),
            ) {
                ReaderSheetHandle()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.reader_appearance_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ReaderSheetColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.reader_appearance_done),
                            style = MaterialTheme.typography.labelLarge,
                            color = ReaderSheetColors.Accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 24.dp),
            ) {
                AppearanceSectionLabel(stringResource(R.string.reader_appearance_text_size))
                TextSizeStepper(settings, onSettingsChanged)

                SectionGap()
                AppearanceSectionLabel(stringResource(R.string.reader_settings_theme_section))
                ThemeSwatches(settings, onSettingsChanged)

                SectionGap()
                AppearanceSectionLabel(stringResource(R.string.reader_appearance_font))
                FontChoices(settings, onSettingsChanged)

                SectionGap()
                AppearanceSectionLabel(stringResource(R.string.reader_appearance_layout))
                ReadingModeChoices(settings, onSettingsChanged)

                SectionGap()
                AppearanceSectionLabel(stringResource(R.string.aa_section_margins))
                MarginControls(settings, onSettingsChanged)

                SectionGap()
                TextLayoutGroup(settings, onSettingsChanged)

                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = {
                            onSettingsChanged(ReaderSettings(customHighlightColors = settings.customHighlightColors))
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.reader_appearance_reset),
                            style = MaterialTheme.typography.labelLarge,
                            color = ReaderSheetColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionGap() = Spacer(Modifier.height(22.dp))

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SplitSettingsSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        SplitSettingsSheet(
            settings = ReaderSettings(),
            onSettingsChanged = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SplitSettingsSheetCustomLayoutPreview() {
    NexoTheme(darkTheme = false) {
        SplitSettingsSheet(
            settings = ReaderSettings(editorValues = false),
            onSettingsChanged = {},
            onDismiss = {},
        )
    }
}

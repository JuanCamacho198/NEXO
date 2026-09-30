package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Plain-data model for a sleep-timer duration preset.
 *
 * @property label Display label for the preset (e.g. `"5"`, `"10"`,
 *   or `"End of chapter"`).
 * @property minutes Duration in minutes. For end-of-chapter presets
 *   this is a sentinel value (typically `0` or `Int.MAX_VALUE`) —
 *   the caller decides how to interpret it.
 * @property isEndOfChapter When `true`, the preset is a
 *   "stop at end of chapter" option rather than a fixed-time
 *   preset. Rendered differently (full-width card with a
 *   description) and excluded from the time-based chips row.
 *   Defaults to `false`.
 */
data class SleepTimerPreset(
    val label: String,
    val minutes: Int,
    val isEndOfChapter: Boolean = false,
)

/**
 * Modal bottom sheet for picking or canceling a sleep-timer
 * duration. Shows the active remaining time (when [isActive] is
 * `true`), a row of time-based preset chips (3 per row), an
 * "End of chapter" full-width card if any preset has
 * `isEndOfChapter = true`, and a cancel action when active.
 *
 * @param isActive `true` when a timer is currently running. Drives
 *   the visibility of the "remaining" header and the cancel button.
 * @param remainingFormatted Pre-formatted remaining time string
 *   (e.g. `"04:32"`). Shown in the active header. The composable
 *   does not format this — the caller is in charge.
 * @param presets All available presets. Time-based presets
 *   (`isEndOfChapter = false`) are rendered as a 3-per-row grid of
 *   chips; end-of-chapter presets each get a full-width
 *   description card.
 * @param onPresetSelected Invoked with the chosen preset's
 *   `minutes` when the user taps any preset. Does NOT auto-close
 *   the sheet.
 * @param onCancel Invoked when the user taps the "Cancel timer"
 *   button. Only rendered when [isActive] is `true`.
 * @param onDismiss Invoked on swipe-down, scrim-tap, back-press, or
 *   when the user taps the close X.
 *
 * **Visual**: standard `ModalBottomSheet` with `surface` background
 *   and 16dp top corners. Header: "Sleep timer" `titleLarge` bold
 *   + close X. If active: a 12dp-rounded `primary`-tinted box
 *   showing "Remaining: HH:MM" in `headlineSmall` bold primary.
 *   Then a section label ("Choose duration" or "Change duration"),
 *   then a `Row` of time-preset chips (`surfaceVariant` background,
 *   primary number, "min" label) in `Arrangement.spacedBy(8.dp)`,
 *   then any end-of-chapter cards (`primary` 10% alpha background
 *   with arrow icon, label, description). If active: a full-width
 *   "Cancel timer" button in `error` 8% alpha background with error
 *   text.
 * **Behavior**: tap a preset chip or end-of-chapter card →
 *   [onPresetSelected]. Tap the cancel button (active only) →
 *   [onCancel]. Tap the close X or swipe → [onDismiss]. No
 *   internal state.
 * **Recomposition**: recomposes when `isActive`, `remainingFormatted`,
 *   `presets`, or callbacks change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    isActive: Boolean,
    remainingFormatted: String,
    presets: List<SleepTimerPreset>,
    onPresetSelected: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_sleep_timer_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = NexoIcons.Close,
                        contentDescription = stringResource(R.string.reader_settings_close),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            if (isActive) {
                // Active timer indicator
                Text(
                    text = "${stringResource(R.string.reader_sleep_timer_remaining_label)} $remainingFormatted",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(vertical = 16.dp, horizontal = 20.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Preset buttons
            Text(
                text = if (isActive) stringResource(R.string.reader_sleep_timer_change_duration) else stringResource(R.string.reader_sleep_timer_choose_duration),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Preset chips — 3 per row, end-of-chapter spans full width
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (timePresets, specialPresets) = presets.partition { !it.isEndOfChapter }

                // Time-based presets (5, 10, 15, 30)
                if (timePresets.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        timePresets.forEach { preset ->
                            TimerPresetChip(
                                label = preset.label,
                                showUnit = true,
                                onClick = { onPresetSelected(preset.minutes) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Special presets (e.g. End of Chapter)
                specialPresets.forEach { preset ->
                    EndOfChapterChip(
                        label = preset.label,
                        description = stringResource(R.string.reader_sleep_timer_end_of_chapter_desc),
                        onClick = { onPresetSelected(preset.minutes) },
                    )
                }
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.reader_sleep_timer_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onCancel() }
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .padding(vertical = 14.dp, horizontal = 20.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TimerPresetChip(
    label: String,
    showUnit: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.5.dp, Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showUnit) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EndOfChapterChip(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Chapter icon
            Icon(
                imageVector = NexoIcons.ArrowForward,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SleepTimerSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        SleepTimerSheet(
            isActive = true,
            remainingFormatted = "04:32",
            presets =
                listOf(
                    SleepTimerPreset(label = "5", minutes = 5),
                    SleepTimerPreset(label = "10", minutes = 10),
                    SleepTimerPreset(label = "15", minutes = 15),
                    SleepTimerPreset(label = "30", minutes = 30),
                    SleepTimerPreset(label = "End of chapter", minutes = 0, isEndOfChapter = true),
                ),
            onPresetSelected = {},
            onCancel = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SleepTimerSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        SleepTimerSheet(
            isActive = true,
            remainingFormatted = "04:32",
            presets =
                listOf(
                    SleepTimerPreset(label = "5", minutes = 5),
                    SleepTimerPreset(label = "10", minutes = 10),
                    SleepTimerPreset(label = "15", minutes = 15),
                    SleepTimerPreset(label = "30", minutes = 30),
                    SleepTimerPreset(label = "End of chapter", minutes = 0, isEndOfChapter = true),
                ),
            onPresetSelected = {},
            onCancel = {},
            onDismiss = {},
        )
    }
}

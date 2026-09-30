package com.nexo.presentation.feature.editmetadata

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexo.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal fun isoToEpochMillis(iso: String?): Long? =
    try {
        iso?.let {
            LocalDate
                .parse(it)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }
    } catch (_: Exception) {
        null
    }

internal fun epochMillisToIso(millis: Long?): String? =
    millis?.let {
        Instant
            .ofEpochMilli(it)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()
    }

internal fun formatPublishedDate(iso: String?): String = if (iso.isNullOrBlank()) "—" else iso

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerField(
    publishedDate: String?,
    onPublishedDateChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                isoToEpochMillis(publishedDate)
                    ?: System.currentTimeMillis(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onPublishedDateChange(epochMillisToIso(millis))
                    }
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

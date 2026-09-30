package com.nexo.presentation.feature.editmetadata

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexo.R
import java.util.Locale

internal val LANGUAGE_OPTIONS =
    listOf(
        "en",
        "es",
        "fr",
        "de",
        "it",
        "pt",
        "nl",
        "ru",
        "zh",
        "ja",
        "ko",
        "ar",
        "pl",
        "sv",
        "tr",
        "el",
        "da",
        "fi",
        "no",
        "cs",
        "hu",
        "ro",
        "uk",
        "hi",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageDropdown(
    selectedCode: String?,
    onSelect: (String) -> Unit,
) {
    val options =
        remember(selectedCode) {
            if (selectedCode != null && LANGUAGE_OPTIONS.none { it.equals(selectedCode, ignoreCase = true) }) {
                listOf(selectedCode) + LANGUAGE_OPTIONS
            } else {
                LANGUAGE_OPTIONS
            }
        }
    var expanded by remember { mutableStateOf(false) }
    val displayName = selectedCode?.let { displayLanguageName(it) }.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.edit_metadata_field_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(text = displayLanguageName(code)) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun displayLanguageName(code: String): String = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())

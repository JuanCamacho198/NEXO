package com.nexo.presentation.feature.editmetadata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.nexo.ui.theme.nexoColors

internal const val MAX_SHORT_FIELD = 200
internal const val MAX_SYNOPSIS = 2000

/** Share of a field's limit after which its character counter appears. */
private const val COUNTER_THRESHOLD = 0.8f

internal val FieldShape = RoundedCornerShape(14.dp)

/** Colours for every input on the form: surface fill, hairline border, primary when focused. */
@Composable
internal fun formFieldColors(): TextFieldColors {
    val colors = nexoColors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        disabledContainerColor = colors.surface,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.borderSubtle,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.textSecondary,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        cursorColor = colors.primary,
    )
}

/**
 * One text input. The character counter only appears once the value passes
 * 80% of [maxLength]: shown permanently under every field it was noise for a
 * limit almost nobody reaches.
 */
@Composable
internal fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    counter: String,
    maxLength: Int,
    singleLine: Boolean = false,
    minLines: Int = 1,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Next,
) {
    val nearLimit = value.length >= maxLength * COUNTER_THRESHOLD
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = singleLine,
        minLines = minLines,
        shape = FieldShape,
        colors = formFieldColors(),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
        supportingText =
            if (nearLimit) {
                {
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (value.length >= maxLength) MaterialTheme.colorScheme.error else nexoColors.textSecondary,
                    )
                }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A titled group of the form: small accented icon, bold heading, then the
 * group's fields spaced evenly. Gives the long form the same rhythm as the
 * Settings pages instead of one undifferentiated column of inputs.
 */
@Composable
internal fun FormSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = nexoColors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

/** Read-only fact about the file (format, size) shown as a quiet pill under the cover. */
@Composable
internal fun InfoPill(text: String) {
    val colors = nexoColors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * A field-shaped button for values picked from a dialog or menu (the date):
 * same height, border and label placement as the text inputs, so the form
 * reads as one set of fields instead of a stray text link.
 */
@Composable
internal fun PickerField(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(FieldShape)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, FieldShape)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
        }
        Icon(imageVector = icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}

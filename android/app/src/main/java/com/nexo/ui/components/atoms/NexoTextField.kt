package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Material 3 `OutlinedTextField` preconfigured with the NEXO color
 * scheme (primary focused border, outline unfocused, error red).
 *
 * @param value Current text content. Must be a hoisted `String` state —
 *   the field does not own its value.
 * @param onValueChange Invoked on every keystroke with the new value.
 * @param modifier Modifier applied to the underlying `OutlinedTextField`.
 *   Note: a `fillMaxWidth()` is applied internally, so width-modifying
 *   modifiers should come before this composable in the call chain.
 * @param label Floating label rendered inside the border when focused or
 *   non-empty. `null` hides it.
 * @param placeholder Hint shown when the field is empty and unfocused.
 *   `null` hides it.
 * @param errorMessage When non-blank, the field enters the error visual
 *   state (red border, red supporting text) and renders the message
 *   below the field.
 * @param leadingIcon Optional icon at the start of the field (e.g. an
 *   envelope for email inputs).
 * @param leadingIconContentDescription Accessibility label for
 *   [leadingIcon]. Pass `null` for a decorative icon.
 * @param trailingIcon Optional icon at the end of the field (e.g. a
 *   visibility toggle for passwords).
 * @param trailingIconContentDescription Accessibility label for
 *   [trailingIcon]. Pass `null` for a decorative icon.
 * @param trailingIconOnClick When set, [trailingIcon] becomes a clickable
 *   [IconButton] (48.dp touch target) invoking this callback. When `null`,
 *   the trailing icon stays decorative — fully backwards-compatible.
 * @param hint Neutral supporting text shown below the field when there is
 *   no error (e.g. "Minimum 8 characters"). Ignored while [errorMessage]
 *   is non-blank (the error message wins).
 * @param singleLine `true` (default) restricts input to one line and
 *   collapses newlines; `false` allows multi-line input.
 * @param enabled `false` disables input and dims the field.
 * @param readOnly `true` blocks editing but keeps the field visually
 *   active (used for selectable-only fields).
 * @param shape Optional corner shape. Falls back to
 *   `OutlinedTextFieldDefaults.shape` when `null`.
 * @param visualTransformation Visual transform applied to the value
 *   (e.g. `PasswordVisualTransformation`). Default `None`.
 *
 * **Visual**: outlined Material 3 field. Border is `colorScheme.primary`
 * when focused, `colorScheme.outline` when unfocused, and
 * `colorScheme.error` when [errorMessage] is non-blank. Supporting text
 * is shown only in the error state.
 * **Behavior**: drives the value via [onValueChange]; parent owns the
 * state. Trailing icon is decorative unless [trailingIconOnClick] is set.
 * **Recomposition**: recomposes when any parameter changes; the
 * `OutlinedTextField` internally skips work if `value` is unchanged.
 */
@Composable
fun NexoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    errorMessage: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconContentDescription: String? = null,
    trailingIcon: ImageVector? = null,
    trailingIconContentDescription: String? = null,
    trailingIconOnClick: (() -> Unit)? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    shape: Shape? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val isError = !errorMessage.isNullOrBlank()

    // Theme-adaptive unfocused border: light slate in dark mode, dark slate in
    // light mode — keeps the field affordance visible in both schemes.
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val unfocusedBorderColor =
        if (isDarkBackground) UnfocusedBorderDarkTheme else UnfocusedBorderLightTheme

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        isError = isError,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon =
            trailingIcon?.let { icon ->
                if (trailingIconOnClick != null) {
                    {
                        IconButton(
                            onClick = trailingIconOnClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = trailingIconContentDescription,
                            )
                        }
                    }
                } else {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = trailingIconContentDescription,
                        )
                    }
                }
            },
        leadingIcon =
            leadingIcon?.let { icon ->
                {
                    Icon(
                        imageVector = icon,
                        contentDescription = leadingIconContentDescription,
                    )
                }
            },
        supportingText =
            if (isError) {
                errorMessage?.let { { Text(it) } }
            } else {
                hint?.let { { Text(it) } }
            },
        visualTransformation = visualTransformation,
        shape = shape ?: OutlinedTextFieldDefaults.shape,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = unfocusedBorderColor,
                errorBorderColor = MaterialTheme.colorScheme.error,
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoTextFieldDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoTextField(
            value = "hello@nextpage.app",
            onValueChange = {},
            label = "Email",
            placeholder = "you@example.com",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTextFieldLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoTextField(
            value = "hello@nextpage.app",
            onValueChange = {},
            label = "Email",
            placeholder = "you@example.com",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTextFieldErrorDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoTextField(
            value = "invalid",
            onValueChange = {},
            label = "Email",
            placeholder = "you@example.com",
            errorMessage = "Invalid email address",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoTextFieldErrorLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoTextField(
            value = "invalid",
            onValueChange = {},
            label = "Email",
            placeholder = "you@example.com",
            errorMessage = "Invalid email address",
        )
    }
}

/** Unfocused field border in dark theme: light slate, visible on the dark canvas. */
private val UnfocusedBorderDarkTheme = Color(0xFF64748B)

/** Unfocused field border in light theme: dark slate, visible on the light canvas. */
private val UnfocusedBorderLightTheme = Color(0xFF475569)

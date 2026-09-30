package com.nexo.presentation.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.nexo.R
import com.nexo.presentation.screen.AuthTags
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    errorMessage: String? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    NexoTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        hint = hint,
        errorMessage = errorMessage,
        leadingIcon = NexoIcons.Lock,
        trailingIcon = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        trailingIconContentDescription =
            stringResource(
                if (passwordVisible) R.string.auth_password_hide else R.string.auth_password_show,
            ),
        trailingIconOnClick = { passwordVisible = !passwordVisible },
        visualTransformation =
            if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(AuthTags.PASSWORD),
    )
}

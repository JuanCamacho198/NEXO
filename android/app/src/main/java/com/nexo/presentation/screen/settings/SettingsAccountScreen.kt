package com.nexo.presentation.screen.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.AuthSession
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.components.atoms.NexoLogoutDialog
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons

@Composable
fun SettingsAccountScreen(
    authSession: AuthSession?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showLogoutDialog) {
        NexoLogoutDialog(
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
            onDismiss = { showLogoutDialog = false },
        )
    }

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_account_title),
        onBack = onBack,
    ) {
        // Card with 24dp padding, avatar 56dp, email, chip Conectado Drive
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NexoAvatar(
                    imageUrl = authSession?.photoUrl,
                    initials =
                        (authSession?.displayName ?: stringResource(R.string.settings_user_default))
                            .take(2)
                            .uppercase(),
                    size = 56.dp,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authSession?.displayName ?: stringResource(R.string.settings_user_default),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = authSession?.email ?: stringResource(R.string.settings_email_placeholder),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = stringResource(R.string.settings_account_connected_drive),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = NexoIcons.CloudSync,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        border = null,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lista con 2 filas: 16dp horizontal, 12dp entre filas, 24dp antes de Cerrar sesión
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Exportar mis datos
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.settings_account_export_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = NexoIcons.Upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_account_export_data),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = NexoIcons.ArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 24dp before Cerrar sesión: spacedBy 12dp + extra 12dp padding
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable(onClick = { showLogoutDialog = true }),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = NexoIcons.SignOut,
                        contentDescription = stringResource(R.string.settings_logout),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_logout),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun SettingsAccountScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        SettingsAccountScreen(
            authSession = null,
            onLogout = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsAccountScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        SettingsAccountScreen(
            authSession = null,
            onLogout = {},
            onBack = {},
        )
    }
}

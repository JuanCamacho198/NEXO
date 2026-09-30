package com.nexo.presentation.feature.legal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant

/**
 * One-time legal disclaimer dialog (U5).
 *
 * Shown once per install until accepted; acceptance is persisted durably
 * through `LegalDisclaimerPrefs`, so the dialog never re-appears after a
 * process restart. [onViewPolicy] opens the full [LegalPolicyScreen];
 * [onAccept] persists acceptance and dismisses.
 */
@Composable
fun LegalNoticeDialog(
    onAccept: () -> Unit,
    onViewPolicy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NexoDialog(
        title = stringResource(R.string.legal_disclaimer_title),
        body = stringResource(R.string.legal_disclaimer_body),
        confirmText = stringResource(R.string.legal_disclaimer_accept),
        dismissText = stringResource(R.string.legal_disclaimer_view_policy),
        onConfirm = onAccept,
        onDismiss = {
            onViewPolicy()
            onDismiss()
        },
        modifier = modifier,
        variant = NexoDialogVariant.INFO,
    )
}

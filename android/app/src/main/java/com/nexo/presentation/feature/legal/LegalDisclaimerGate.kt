package com.nexo.presentation.feature.legal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nexo.data.session.LegalDisclaimerPrefs

/**
 * One-time legal disclaimer gate for a host screen.
 *
 * Owns the durable acceptance check and renders [LegalNoticeDialog] until the
 * reader accepts. It is a component rather than an inline `if` block because a
 * `composable { }` lambda body is not a `@Composable` context: navigation cannot
 * be invoked from inside the dialog's argument lambdas when they are written
 * directly in the nav graph.
 *
 * @param onNavigateToLegalPolicy Opens the full legal policy page.
 * @return true once acceptance is recorded, so the host can keep its own body
 *   hidden while the dialog is up.
 */
@Composable
fun LegalDisclaimerGate(
    onNavigateToLegalPolicy: () -> Unit,
): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { LegalDisclaimerPrefs(context) }
    var accepted by remember { mutableStateOf(prefs.hasAccepted()) }
    if (!accepted) {
        LegalNoticeDialog(
            onAccept = {
                prefs.accept()
                accepted = true
            },
            onViewPolicy = onNavigateToLegalPolicy,
            onDismiss = { accepted = prefs.hasAccepted() },
        )
    }
    return accepted
}

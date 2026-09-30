package com.nexo.presentation.screen.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nexo.R
import com.nexo.presentation.screen.settings.performance.DiagnosticsCard
import com.nexo.presentation.screen.settings.performance.ResourcesCard
import com.nexo.presentation.screen.settings.performance.SyncCard
import com.nexo.presentation.screen.settings.performance.TimingsCard
import com.nexo.presentation.viewmodel.PerformanceViewModel
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import java.io.File

@Composable
fun PerformanceScreen(
    onBack: () -> Unit,
    viewModel: PerformanceViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_performance_title),
        onBack = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimingsCard(
                timings = uiState.timings,
                lastMeasuredAt = uiState.lastMeasuredAt,
                isMeasuring = uiState.isMeasuring,
                onMeasureNow = { viewModel.measureNow() },
            )
            ResourcesCard(
                resources = uiState.resources,
                isClearingCache = uiState.isClearingCache,
                onClearCache = { ctx ->
                    viewModel.clearCache { success, _ ->
                        Toast
                            .makeText(
                                ctx,
                                if (success) {
                                    ctx.getString(R.string.performance_cache_cleared)
                                } else {
                                    ctx.getString(R.string.performance_cache_clear_error)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                },
            )
            SyncCard(syncStatus = uiState.syncStatus)
            DiagnosticsCard(
                diagnostics = uiState.diagnostics,
                isGenerating = uiState.isGeneratingReport,
                reportPath = uiState.reportPath,
                onGenerateReport = { ctx ->
                    viewModel.generateReport { file ->
                        if (file != null) {
                            Toast
                                .makeText(
                                    ctx,
                                    ctx.getString(R.string.performance_report_generated, file.name),
                                    Toast.LENGTH_LONG,
                                ).show()
                            shareFile(ctx, file)
                        } else {
                            Toast
                                .makeText(
                                    ctx,
                                    ctx.getString(R.string.performance_report_error),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                },
                onShareFile = { ctx, file -> shareFile(ctx, file) },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun shareFile(
    context: android.content.Context,
    file: File,
) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.performance_share_report)))
    }
}

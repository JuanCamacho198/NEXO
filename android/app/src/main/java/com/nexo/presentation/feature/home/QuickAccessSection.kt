package com.nexo.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.icons.NexoIcons

@Composable
fun QuickAccessSection(
    onImportBook: () -> Unit,
    onHighlights: () -> Unit,
    onStatistics: () -> Unit,
    onSettings: () -> Unit,
) {
    Column {
        Text(text = stringResource(R.string.home_quick_actions), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Column(verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                QuickAccessButton(icon = NexoIcons.Upload, label = stringResource(R.string.home_action_import_book), onClick = onImportBook, modifier = Modifier.weight(1f))
                QuickAccessButton(icon = NexoIcons.Bookmark, label = stringResource(R.string.home_highlights), onClick = onHighlights, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                QuickAccessButton(icon = NexoIcons.Statistics, label = stringResource(R.string.home_action_stats), onClick = onStatistics, modifier = Modifier.weight(1f))
                QuickAccessButton(icon = NexoIcons.Settings, label = stringResource(R.string.home_settings), onClick = onSettings, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun QuickAccessButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(NexoDimens.spacingMd), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
            Text(text = label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

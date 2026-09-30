package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GOOGLEBOOKS
import com.nexo.data.remote.catalog.CatalogSources
import com.nexo.presentation.theme.NexoColors

/**
 * fs12 source attribution badge for catalog results.
 *
 * Two sources render it: addon sources (`addon:<addonId>`, name from
 * [attributionNames] with generic "Addon" fallback) and Google Books
 * (`builtin:googlebooks`, fixed "Powered by Google" string per the Books API
 * attribution requirement). Any other built-in/curated provider is
 * first-party and renders nothing.
 */
@Composable
fun SourceAttributionBadge(
    provider: String,
    attributionNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    if (provider == BUILTIN_GOOGLEBOOKS) {
        Text(
            text = stringResource(R.string.google_books_attribution),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium,
            color = NexoColors.textSecondary,
            modifier =
                modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NexoColors.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        return
    }
    val addonId = CatalogSources.addonIdOf(provider) ?: return
    val label =
        attributionNames[addonId]
            ?: stringResource(R.string.discover_source_addon_fallback)

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        fontWeight = FontWeight.Medium,
        color = NexoColors.textSecondary,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(NexoColors.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

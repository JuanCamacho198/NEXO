package com.nexo.presentation.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.domain.access.AccessGroup
import com.nexo.domain.access.AccessOption
import com.nexo.domain.access.LegalAccess
import com.nexo.domain.access.resolveAccess
import com.nexo.presentation.theme.NexoColors

/** Group header order for the access section: free first, paid last. */
private val ACCESS_GROUP_ORDER = listOf(AccessGroup.FREE, AccessGroup.BUY, AccessGroup.SUBSCRIBE)

/** Localized label for an [AccessGroup] (reuses the U3 group strings). */
@Composable
internal fun accessGroupLabel(group: AccessGroup): String =
    stringResource(
        when (group) {
            AccessGroup.FREE -> R.string.access_group_free
            AccessGroup.BUY -> R.string.access_group_buy
            AccessGroup.SUBSCRIBE -> R.string.access_group_subscribe
        },
    )

/**
 * Availability badge for cards and rails (U5).
 *
 * Shows the free-download affordance when the book resolves to an in-app
 * public-domain download, else the first available paid/free group label.
 * Renders nothing when the book has no openable option at all.
 */
@Composable
fun AccessBadge(
    book: CatalogBook,
    modifier: Modifier = Modifier,
) {
    val access = remember(book) { resolveAccess(book) }
    AccessBadge(access = access, modifier = modifier)
}

/** Badge over an already-resolved [LegalAccess]. */
@Composable
fun AccessBadge(
    access: LegalAccess,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            access.canDownloadInApp -> stringResource(R.string.access_badge_download)
            else -> access.options.firstOrNull()?.let { firstGroupLabel(it.group) }
        } ?: return
    val icon = if (access.canDownloadInApp) Icons.Rounded.Download else null
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(NexoColors.backgroundGreenTransparent)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = NexoColors.accentGreen,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium,
            color = NexoColors.accentGreen,
        )
    }
}

@Composable
private fun firstGroupLabel(group: AccessGroup): String = accessGroupLabel(group)

/**
 * Provider logo tile: the source's initial on the card surface, shared by
 * access rows so addon/builtin rows carry a stable visual anchor without
 * shipping per-provider drawables.
 */
@Composable
fun ProviderLogo(
    label: String,
    modifier: Modifier = Modifier,
) {
    val initial =
        remember(label) {
            label.trim().firstOrNull()?.uppercase() ?: PROVIDER_LOGO_FALLBACK
        }
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NexoColors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NexoColors.primary,
        )
    }
}

/**
 * External-open CTA for one access option: provider logo, option title and
 * an explicit open-in-browser action. Links stay web-only (`https`,
 * external open) — this button never downloads in-app.
 */
@Composable
fun AccessCtaButton(
    option: AccessOption,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenExternal(option.url) }
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderLogo(label = option.title)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = option.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = NexoColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.OpenInNew,
            contentDescription = stringResource(R.string.read_access_open),
            tint = NexoColors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Grouped access rows: FREE / BUY / SUBSCRIBE sections in that order, each
 * with its localized header and external-open rows. Groups without options
 * are skipped (no empty headers).
 */
@Composable
fun BookAccessList(
    access: LegalAccess,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ACCESS_GROUP_ORDER.forEach { group ->
            val rows = access.options.filter { it.group == group }
            if (rows.isNotEmpty()) {
                Text(
                    text = accessGroupLabel(group),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = NexoColors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                rows.forEach { option ->
                    AccessCtaButton(option = option, onOpenExternal = onOpenExternal)
                }
            }
        }
    }
}

/** Fallback glyph for a blank provider label (never a blank tile). */
private const val PROVIDER_LOGO_FALLBACK = "?"

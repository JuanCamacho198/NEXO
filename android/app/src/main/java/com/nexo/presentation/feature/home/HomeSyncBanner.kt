package com.nexo.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.screen.HomeTags
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.molecules.NexoCardSurface
import com.nexo.ui.icons.NexoIcons

/** Diameter of the cloud glyph's circular container. */
private val SyncIconWrapSize = 44.dp

/** Side length of the cloud glyph inside its container. */
private val SyncIconSize = 22.dp

/**
 * Cloud-sync sign-in banner for the first-session Home.
 *
 * A bordered surface card (`NexoCardSurface`) with a blue disc holding the
 * cloud glyph, the two-line pitch, and a chevron — the `.pen` `sync_banner`
 * node (`W5lCEx`). The whole card is one tap target: it leads to the sign-in
 * that enables Drive sync, it is not a set of controls.
 *
 * @param onClick Invoked when the banner is tapped.
 */
@Composable
fun HomeSyncBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(R.string.home_sync_banner_content_description)
    NexoCardSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClickLabel = contentDescription, onClick = onClick)
                .testTag(HomeTags.SYNC_BANNER),
        contentPadding = PaddingValues(NexoDimens.spacingMd),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(SyncIconWrapSize)
                        .clip(CircleShape)
                        .background(NexoColors.iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(SyncIconSize),
                    tint = NexoColors.accentYellow,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.home_sync_banner_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NexoColors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.home_sync_banner_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexoColors.textSecondary,
                )
            }
            Icon(
                imageVector = NexoIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = NexoColors.textSecondary,
            )
        }
    }
}

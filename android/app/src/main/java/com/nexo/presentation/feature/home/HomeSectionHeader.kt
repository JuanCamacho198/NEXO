package com.nexo.presentation.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

/** Minimum touch height of the trailing "Ver todo" link. */
private val ActionMinHeight = 40.dp

/**
 * Heading shared by every Home section: a bold title on the left and, when
 * [actionLabel] is given, an amber link with a chevron on the right.
 */
@Composable
internal fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = stringResource(R.string.home_ver_todo),
    onAction: (() -> Unit)? = null,
) {
    val colors = nexoColors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onAction != null) {
            Row(
                modifier =
                    Modifier
                        .heightIn(min = ActionMinHeight)
                        .pressScaleClickable(shape = CircleShape, onClickLabel = actionLabel, onClick = onAction)
                        .padding(start = NexoDimens.spacingSm, end = NexoDimens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accentOrange,
                    )
                }
                Icon(
                    imageVector = NexoIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (actionLabel != null) colors.accentOrange else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

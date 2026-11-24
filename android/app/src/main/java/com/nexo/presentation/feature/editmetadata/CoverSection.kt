package com.nexo.presentation.feature.editmetadata

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.nexoColors
import com.nexo.ui.R as UiR

/**
 * The cover as the head of the form: centred, lifted with a shadow, and itself
 * the control that changes it (a pencil badge says so), with a text action
 * underneath for anyone who does not try the cover. The file's format and size
 * sit below as pills: facts about the book, not editable fields.
 *
 * @param sizeLabel Human-readable file size, or `null` when unknown.
 */
@Composable
internal fun CoverSection(
    book: Book,
    coverUri: Uri?,
    sizeLabel: String?,
    onChangeCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverShape = RoundedCornerShape(14.dp)
    val changeCoverLabel = stringResource(R.string.edit_metadata_change_cover)
    val coverRequest =
        remember(context, density, coverUri, book.coverPath) {
            ImageRequest
                .Builder(context)
                .data(coverUri ?: book.coverPath?.takeIf { it.isNotBlank() })
                .size(
                    width = with(density) { CoverWidth.toPx().toInt() },
                    height = with(density) { CoverHeight.toPx().toInt() },
                ).placeholder(UiR.drawable.cover_placeholder)
                .error(UiR.drawable.cover_error)
                .fallback(UiR.drawable.cover_placeholder)
                .crossfade(true)
                .build()
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.12f), Color.Transparent)))
                .padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier =
                    Modifier
                        .size(CoverWidth, CoverHeight)
                        .shadow(elevation = 14.dp, shape = coverShape)
                        .pressScaleClickable(shape = coverShape, onClickLabel = changeCoverLabel, onClick = onChangeCover)
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.borderSubtle, coverShape),
            ) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = stringResource(R.string.library_cover_content_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .size(38.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .border(3.dp, colors.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.Pencil,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        TextButton(onClick = onChangeCover) {
            Text(text = changeCoverLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPill(text = book.format.uppercase())
            if (sizeLabel != null) InfoPill(text = sizeLabel)
        }
    }
}

private val CoverWidth = 132.dp
private val CoverHeight = 198.dp

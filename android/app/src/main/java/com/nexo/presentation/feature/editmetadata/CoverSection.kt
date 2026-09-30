package com.nexo.presentation.feature.editmetadata

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant

@Composable
internal fun CoverSection(
    book: Book,
    coverUri: Uri?,
    onChangeCover: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverRequest =
        remember(context, density, coverUri, book.coverPath) {
            ImageRequest
                .Builder(context)
                .data(coverUri ?: book.coverPath?.takeIf { it.isNotBlank() })
                .size(
                    width = with(density) { 128.dp.toPx().toInt() },
                    height = with(density) { 192.dp.toPx().toInt() },
                ).placeholder(R.drawable.cover_placeholder)
                .error(R.drawable.cover_error)
                .fallback(R.drawable.cover_placeholder)
                .crossfade(true)
                .build()
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier =
                Modifier
                    .size(128.dp, 192.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = coverRequest,
                contentDescription = stringResource(R.string.library_cover_content_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        NexoButton(
            text = stringResource(R.string.edit_metadata_change_cover),
            onClick = onChangeCover,
            variant = NexoButtonVariant.OUTLINED,
        )
    }
}

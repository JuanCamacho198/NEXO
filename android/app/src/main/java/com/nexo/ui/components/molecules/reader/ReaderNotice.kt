package com.nexo.ui.components.molecules.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.event.NoticeTone
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.delay

/**
 * A reader notice ("Copied", "Added to your dictionary"). Carries the tone so
 * the surface can mark success, information and failure apart. Timed by
 * [ReaderNoticeHost], not by Material's 4s/10s durations: a confirmation that
 * outstays the action reads as a second thing to deal with.
 */
class ReaderNoticeVisuals(
    override val message: String,
    val tone: NoticeTone,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}

private const val CONFIRMATION_MS = 2_200L
private const val ERROR_MS = 4_500L

/** How long a notice stays: errors long enough to read twice, confirmations just long enough to register. */
internal fun noticeDurationMs(tone: NoticeTone): Long = if (tone == NoticeTone.ERROR) ERROR_MS else CONFIRMATION_MS

/**
 * The reader's notice host. Same navy and outline as the reader's menus and
 * sheets, so a notice reads as part of the reader rather than a system toast.
 * A tap dismisses it early.
 */
@Composable
fun ReaderNoticeHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val tone = (data.visuals as? ReaderNoticeVisuals)?.tone ?: NoticeTone.INFO
        LaunchedEffect(data) {
            delay(noticeDurationMs(tone))
            data.dismiss()
        }
        ReaderNotice(message = data.visuals.message, tone = tone, onDismiss = data::dismiss)
    }
}

private val NoticeShape = RoundedCornerShape(22.dp)

@Composable
internal fun ReaderNotice(
    message: String,
    tone: NoticeTone,
    onDismiss: () -> Unit = {},
) {
    val accent = toneColor(tone)
    Row(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 420.dp)
                .shadow(10.dp, NoticeShape)
                .clip(NoticeShape)
                .background(ReaderSheetColors.Surface)
                .border(1.dp, ReaderSheetColors.Outline, NoticeShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .semantics { liveRegion = if (tone == NoticeTone.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector =
                    when (tone) {
                        NoticeTone.SUCCESS -> NexoIcons.Check
                        NoticeTone.INFO -> NexoIcons.Info
                        NoticeTone.ERROR -> NexoIcons.ErrorOutline
                    },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = ReaderSheetColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun toneColor(tone: NoticeTone): Color =
    when (tone) {
        NoticeTone.SUCCESS -> Color(0xFF4ADE80)
        NoticeTone.INFO -> ReaderSheetColors.Accent
        NoticeTone.ERROR -> Color(0xFFF87171)
    }

@Preview(showBackground = true, backgroundColor = 0xFFFAF7F0)
@Composable
private fun ReaderNoticePreview() {
    androidx.compose.foundation.layout.Column {
        ReaderNotice(message = "Copiado al portapapeles", tone = NoticeTone.SUCCESS)
        ReaderNotice(message = "\"descontento\" ya está en tu diccionario.", tone = NoticeTone.INFO)
        ReaderNotice(message = "No se pudo guardar. Inténtelo de nuevo.", tone = NoticeTone.ERROR)
    }
}

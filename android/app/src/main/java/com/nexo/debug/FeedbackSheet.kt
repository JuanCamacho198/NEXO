package com.nexo.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme

/**
 * Mobile crash-feedback bottom sheet (PR4 / tasks 4.3-4.4).
 *
 * Pixel-faithful port of the njdtk node from `design/nextPage-movil.pen`
 * (per feedback-design contract #2460):
 * - bg #0F1419, card #1A1F26, input #222932/#0F1419, borders #2D3441/#3A4150
 * - text #F5F5F5 / #9CA3AF / #6B7280
 * - accent peach #F5A88C, primary gradient #F5A88C → #E8845F
 * - dark text on primary #1A0F08
 *
 * Layout (top → bottom):
 *  1. Status-bar placeholder (transparent; the system bar paints under the sheet)
 *  2. Card (#1A1F26):
 *       - icon wrap 64x64 (peach tint + Favorite)
 *       - title "Ayúdanos a arreglar esto" (22sp, w700, center)
 *       - subtitle (14sp, w400)
 *       - auto-context card (sparkles + 44x60 cover + "La Odisea" + "Capítulo IX · página 142 de 318")
 *       - textarea (cap 240 + live counter "n / 240" + helper)
 *       - privacy shield-check
 *       - Cerrar (secondary) + Enviar reporte (gradient primary)
 *  3. Home indicator (transparent)
 *
 * State machine: idle / editing / sending / sent / error — all transitions
 * happen through [FeedbackViewModel]. The composable is a thin render layer.
 *
 * No pills on mobile (desktop-only feature per feedback-design).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(
    viewModel: FeedbackViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val state by viewModel.state.collectAsState()
    val charCount by viewModel.charCount.collectAsState()
    val canSubmit by viewModel.canSubmit.collectAsState()

    // Don't show the sheet if the user already dismissed this eventId.
    if (state is FeedbackEvent.FeedbackSheetState.Idle) {
        onDismiss()
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = FeedbackTokens.BgCanvas,
        scrimColor = FeedbackTokens.Scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
        ) {
            FeedbackCard(
                state = state,
                book = viewModel.bookMeta,
                charCount = charCount,
                canSubmit = canSubmit,
                onTextChanged = viewModel::onTextChanged,
                onSubmit = viewModel::submit,
                onDismiss = {
                    viewModel.dismiss()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun FeedbackCard(
    state: FeedbackEvent.FeedbackSheetState,
    book: FeedbackEvent.BookMeta,
    charCount: Int,
    canSubmit: Boolean,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editing = state as? FeedbackEvent.FeedbackSheetState.Editing
    val editingText = editing?.text ?: ""
    val isSending = state is FeedbackEvent.FeedbackSheetState.Sending
    val isSent = state is FeedbackEvent.FeedbackSheetState.Sent
    val error = state as? FeedbackEvent.FeedbackSheetState.Error

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(FeedbackTokens.BgCard)
                .padding(20.dp),
    ) {
        // 1. Icon wrap 64x64 (peach tint + Favorite)
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(FeedbackTokens.AccentPeach.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Favorite,
                contentDescription = null,
                tint = FeedbackTokens.AccentPeach,
                modifier = Modifier.size(32.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Title 22sp / w700 / center
        Text(
            text = stringResource(R.string.feedback_mobile_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = FeedbackTokens.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Subtitle 14sp / w400
        Text(
            text = stringResource(R.string.feedback_mobile_subtitle),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = FeedbackTokens.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 4. Auto-context card
        FeedbackAutoContextCard(book = book)

        Spacer(modifier = Modifier.height(20.dp))

        // 5. Textarea (cap 240 + live counter + helper)
        FeedbackTextArea(
            text = editingText,
            charCount = charCount,
            isSending = isSending,
            onTextChanged = onTextChanged,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Privacy shield-check
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = FeedbackTokens.TextTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.feedback_mobile_privacy),
                fontSize = 12.sp,
                color = FeedbackTokens.TextTertiary,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 7. Action row (Cerrar + Enviar reporte)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = FeedbackTokens.BgInput,
                        contentColor = FeedbackTokens.TextPrimary,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.feedback_mobile_close),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit && !isSending && !isSent,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = FeedbackTokens.AccentPeach,
                        contentColor = FeedbackTokens.OnPrimary,
                    ),
            ) {
                Text(
                    text =
                        when {
                            isSending -> stringResource(R.string.feedback_mobile_sending)
                            isSent -> stringResource(R.string.feedback_mobile_sent)
                            else -> stringResource(R.string.feedback_mobile_send)
                        },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.feedback_mobile_offline_notice),
                fontSize = 12.sp,
                color = FeedbackTokens.TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FeedbackAutoContextCard(book: FeedbackEvent.BookMeta) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(FeedbackTokens.BgCardAccent)
                .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = FeedbackTokens.AccentPeach,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.feedback_mobile_auto_context_label),
                    fontSize = 11.sp,
                    color = FeedbackTokens.TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val title = book.title ?: stringResource(R.string.feedback_mobile_sample_book)
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FeedbackTokens.TextPrimary,
                )
                val page = book.page
                val chapter = book.chapterLabel
                val chapterText =
                    if (chapter != null && page != null) {
                        "$chapter · página $page de 318"
                    } else if (chapter != null) {
                        chapter
                    } else if (page != null) {
                        "página $page"
                    } else {
                        stringResource(R.string.feedback_mobile_sample_chapter)
                    }
                Text(
                    text = chapterText,
                    fontSize = 12.sp,
                    color = FeedbackTokens.TextTertiary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 44x60 cover (peach gradient mimic per design — design uses book cover; we use
            // a small placeholder gradient block since the design sample is "La Odisea"
            // and we don't have cover art bound at runtime in this path).
            Box(
                modifier =
                    Modifier
                        .size(width = 44.dp, height = 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        FeedbackTokens.CoverTop,
                                        FeedbackTokens.CoverBottom,
                                    ),
                            ),
                        ),
            )
        }
    }
}

@Composable
private fun FeedbackTextArea(
    text: String,
    charCount: Int,
    isSending: Boolean,
    onTextChanged: (String) -> Unit,
) {
    val cursorColor = FeedbackTokens.AccentPeach
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = text,
            onValueChange = onTextChanged,
            enabled = !isSending,
            textStyle =
                TextStyle(
                    color = FeedbackTokens.TextPrimary,
                    fontSize = 14.sp,
                ),
            cursorBrush = SolidColor(cursorColor),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(FeedbackTokens.BgInput)
                    .padding(14.dp),
        )
        // Placeholder + counter via a DecorationBox so we can render label + count.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.feedback_mobile_helper),
                fontSize = 11.sp,
                color = FeedbackTokens.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        R.string.feedback_mobile_counter,
                        charCount,
                        FeedbackViewModel.MAX_CHARS,
                    ),
                fontSize = 11.sp,
                color =
                    if (charCount >= FeedbackViewModel.MAX_CHARS) {
                        FeedbackTokens.AccentPeach
                    } else {
                        FeedbackTokens.TextTertiary
                    },
            )
        }
    }
}

/**
 * Mobile-specific design tokens — colors mirror feedback-design #2460 (njdtk).
 * Kept inline (not in Color.kt) because these are crash-feedback-only and
 * would otherwise pollute the global palette.
 */
private object FeedbackTokens {
    val BgCanvas = Color(0xFF0F1419)
    val BgCard = Color(0xFF1A1F26)
    val BgCardAccent = Color(0xFF222932)
    val BgInput = Color(0xFF0F1419)
    val BorderDefault = Color(0xFF2D3441)
    val BorderStrong = Color(0xFF3A4150)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextTertiary = Color(0xFF6B7280)
    val AccentPeach = Color(0xFFF5A88C)
    val AccentPeachDeep = Color(0xFFE8845F)
    val OnPrimary = Color(0xFF1A0F08)
    val CoverTop = Color(0xFF4A3F35)
    val CoverBottom = Color(0xFF2A2520)
    val Scrim = Color(0x66000000)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF0F1419)
@Composable
private fun FeedbackSheetPreview() {
    NexoTheme(darkTheme = true) {
        // Preview-only — the real ViewModel is constructed by the host activity.
        val previewVm =
            remember {
                FeedbackViewModel(
                    initialQueue = emptyList(),
                    initialDismissed = emptySet(),
                    initialBook =
                        FeedbackEvent.BookMeta(
                            bookId = "odisea",
                            title = "La Odisea",
                            chapterLabel = "Capítulo IX",
                            chapterIndex = 9,
                            page = 142,
                        ),
                    initialEventId = "abc123def456",
                    captureFn = { null },
                )
            }
        FeedbackSheet(viewModel = previewVm, onDismiss = {})
    }
}

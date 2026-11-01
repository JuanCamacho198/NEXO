package com.nexo.debug

import androidx.annotation.StringRes
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
import com.nexo.ui.components.atoms.CoverThumbnail

/**
 * Mobile crash-feedback bottom sheet (PR4 / tasks 4.3-4.4).
 *
 * Port of the njdtk node from `design/nextPage-movil.pen` (per feedback-design
 * contract #2460), with the surface colors aligned to NEXO's own dark palette
 * (`presentation/theme/Color.kt`) — see [FeedbackTokens].
 * - canvas #0B1120 (NEXO BgMain) with a brand wash at the top
 * - card #161F33, input #0B1120
 * - text #F5F5F5 / #9CA3AF / #6B7280
 * - accent peach #F5A88C, primary #F5A88C → #E8845F
 * - dark text on primary #1A0F08
 *
 * Layout (top → bottom):
 *  1. Status-bar placeholder (transparent; the system bar paints under the sheet)
 *  2. Card (#161F33):
 *       - icon wrap 64x64 (peach tint + Favorite)
 *       - title "Ayúdanos a arreglar esto" (22sp, w700, center)
 *       - subtitle (14sp, w400)
 *       - auto-context card (sparkles + optional 44x60 real cover + title + "Capítulo II · página 30").
 *         Built from [CrashContext]: a book card when the crash happened while
 *         reading, a plain screen row otherwise — never invented book data.
 *         The page line carries no total (only the current page is known).
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
                    .background(
                        // Brand wash: this Activity cannot show the real screen
                        // behind it (the previous process is gone), so the sheet
                        // carries NEXO's own canvas instead of flat black.
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    FeedbackTokens.CanvasWash,
                                    FeedbackTokens.BgCanvas,
                                ),
                        ),
                    )
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
        ) {
            FeedbackCard(
                state = state,
                context = viewModel.context,
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
    context: CrashContextViewModel,
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
        FeedbackAutoContextCard(context = context)

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
private fun FeedbackAutoContextCard(context: CrashContextViewModel) {
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
                Text(
                    text = context.primaryLabel(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FeedbackTokens.TextPrimary,
                )
                Text(
                    text = context.secondaryLabel(),
                    fontSize = 12.sp,
                    color = FeedbackTokens.TextTertiary,
                )
            }
            if (context.hasCover) {
                Spacer(modifier = Modifier.width(12.dp))
                // Real cover when the book has one. No cover = no thumbnail:
                // the old shared brown rectangle claimed every book looked alike.
                CoverThumbnail(
                    coverPath = context.coverPath,
                    modifier = Modifier.size(width = 44.dp, height = 60.dp),
                )
            }
        }
    }
}

/**
 * Headline of the context card: the book title, or the screen the user was on.
 * A book without a title falls back to a generic label instead of the previous
 * hardcoded "La Odisea".
 */
@Composable
private fun CrashContextViewModel.primaryLabel(): String =
    when {
        isBook -> bookTitle ?: stringResource(R.string.feedback_mobile_context_no_title)
        screenRoute != null -> stringResource(screenRouteLabelRes(screenRoute))
        else -> stringResource(R.string.feedback_mobile_context_unknown_screen)
    }

/**
 * Second line: "Capítulo II · página 30" for books (only the parts we actually
 * know — never an invented total), a plain sentence for non-book screens.
 */
@Composable
private fun CrashContextViewModel.secondaryLabel(): String =
    if (isBook) {
        val line = chapterLine
        when {
            line?.chapterLabel != null && line.page != null ->
                stringResource(R.string.feedback_mobile_context_chapter_page, line.chapterLabel, line.page)

            line?.page != null -> stringResource(R.string.feedback_mobile_context_page_only, line.page)
            line?.chapterLabel != null -> stringResource(R.string.feedback_mobile_context_chapter_only, line.chapterLabel)
            else -> stringResource(R.string.feedback_mobile_context_no_book)
        }
    } else {
        stringResource(R.string.feedback_mobile_context_no_book)
    }

/** Map a navigation route onto the existing localized tab labels. */
@StringRes
private fun screenRouteLabelRes(route: String): Int =
    when (route) {
        "home" -> R.string.nav_home
        "library" -> R.string.nav_library
        "discover" -> R.string.nav_discover
        "highlights" -> R.string.nav_highlights
        "settings" -> R.string.nav_settings
        "statistics" -> R.string.nav_statistics
        else -> R.string.feedback_mobile_context_unknown_screen
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
 * Mobile-specific design tokens.
 *
 * The surface colors were originally the flat near-black `#0F1419` from the
 * njdtk design node; the sheet is a separate Activity so it could not show the
 * real screen behind it, and that flat black never matched NEXO. They now
 * mirror the app's own dark canvas (`BgMain`/`BgSurface` in
 * `presentation/theme/Color.kt`) so the sheet reads as part of NEXO instead of
 * an unrelated system dialog. Peach accents are unchanged.
 */
private object FeedbackTokens {
    val BgCanvas = Color(0xFF0B1120)
    val BgCard = Color(0xFF161F33)
    val BgCardAccent = Color(0xFF1E293B)
    val BgInput = Color(0xFF0B1120)
    val BorderDefault = Color(0xFF2D3441)
    val BorderStrong = Color(0xFF3A4150)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextTertiary = Color(0xFF6B7280)
    val AccentPeach = Color(0xFFF5A88C)
    val AccentPeachDeep = Color(0xFFE8845F)
    val OnPrimary = Color(0xFF1A0F08)
    val Scrim = Color(0x66000000)

    /** Brand wash at the top of the canvas — evokes NEXO without the real screen. */
    val CanvasWash = Color(0xFF1B2440)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF0B1120)
@Composable
private fun FeedbackSheetPreview() {
    NexoTheme(darkTheme = true) {
        // Preview-only — the real ViewModel is constructed by the host activity.
        val previewVm =
            remember {
                FeedbackViewModel(
                    initialQueue = emptyList(),
                    initialDismissed = emptySet(),
                    initialContext =
                        CrashContext.InBook(
                            bookId = "extranjero",
                            title = "El extranjero",
                            chapterLabel = "Capítulo II",
                            chapterIndex = 2,
                            page = 30,
                        ),
                    initialEventId = "abc123def456",
                    captureFn = { null },
                )
            }
        FeedbackSheet(viewModel = previewVm, onDismiss = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF0B1120, name = "No book context")
@Composable
private fun FeedbackSheetNoBookPreview() {
    NexoTheme(darkTheme = true) {
        val previewVm =
            remember {
                FeedbackViewModel(
                    initialQueue = emptyList(),
                    initialDismissed = emptySet(),
                    initialContext = CrashContext.InScreen(screenRoute = "settings"),
                    initialEventId = "abc123def456",
                    captureFn = { null },
                )
            }
        FeedbackSheet(viewModel = previewVm, onDismiss = {})
    }
}

package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Glassmorphic full-screen modal for adding or editing a personal
 * note/comment on a highlighted text selection. Shows the selected
 * text snippet (when [selectedText] is non-blank) and a 120dp-tall
 * textarea. No color selector — explicitly removed per design spec
 * CM03.
 *
 * Design node `GshXP` (Pencil):
 * - Backdrop: full-screen black, tap = dismiss.
 * - Modal: 360dp wide (max 512dp), 24dp rounded, fill `#101C2C`,
 *   stroke `#1794ADCE`, 24dp padding.
 * - Header: title (18sp white bold) + 32dp circular close button.
 * - Snippet: left 4dp cyan border (`#49D4FF`), 8dp rounded
 *   background (`#4008111F`), 6-line-clipped text.
 * - Textarea: `#08111F` background, 16dp rounded, 120dp tall, cyan
 *   cursor.
 * - Actions: "Cancel" (`#8FA3BF` text) + "Save" (cyan
 *   `#49D4FF` background, dark text, 50% rounded pill).
 *
 * @param titleRes String resource for the modal title (e.g.
 *   "New note" / "New comment").
 * @param hintRes String resource for the textarea placeholder.
 * @param snippetLabelRes String resource for the label above the
 *   highlighted text snippet (e.g. "Selected text"). The whole
 *   snippet area is hidden when [selectedText] is null/blank.
 * @param selectedText The highlighted text to display. When
 *   `null`/blank, the snippet area is omitted entirely.
 * @param initialText Pre-filled textarea text. Use for editing an
 *   existing note. The local form state is `remember(initialText)`-
 *   keyed, so swapping the initial value re-initializes the form.
 *   Default `""`.
 * @param onSave Invoked with the final textarea text when the user
 *   taps "Save".
 * @param onDismiss Invoked when the user taps the backdrop or the
 *   close X. Does NOT save.
 * @param modifier Modifier applied to the backdrop `Box`.
 *
 * **Visual**: full-screen black backdrop → centered 360dp dark
 *   card. Header row (title + close), optional snippet block, 120dp
 *   textarea, action row.
 * **Behavior**: tap on the backdrop (outside the card) → [onDismiss].
 *   Tap on the card body does NOT dismiss (consumed by a
 *   no-indication `clickable`). Tapping the close X also dismisses.
 *   The card has no IME action wired — the user must tap "Save"
 *   explicitly to commit.
 * **Recomposition**: recomposes when any string/int resource, the
 *   selected text, or callbacks change. Local `text` state is
 *   hoisted via `remember(initialText)`.
 */
@Composable
fun HighlightAnnotationModal(
    titleRes: Int,
    hintRes: Int,
    snippetLabelRes: Int,
    selectedText: String?,
    initialText: String = "",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    // ── Full-screen glassmorphic backdrop ──────────────────────────
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    onDismiss()
                },
        contentAlignment = Alignment.Center,
    ) {
        // ── Prevent backdrop tap from propagating through the modal ──
        Box(
            modifier =
                Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { /* consume — don't dismiss on modal tap */ },
        ) {
            // ── Modal card ─────────────────────────────────────────
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 512.dp)
                        .width(360.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF101C2C))
                        .border(1.dp, Color(0x1794ADCE), RoundedCornerShape(24.dp))
                        .padding(24.dp),
            ) {
                // ── Header: title + close ──────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(titleRes),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0x1494ADCE))
                                .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NexoIcons.Close,
                            contentDescription =
                                stringResource(
                                    R.string.annotation_modal_close,
                                ),
                            tint = Color(0xFF8FA3BF),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Highlighted text snippet ───────────────────────
                if (!selectedText.isNullOrBlank()) {
                    Text(
                        text = stringResource(snippetLabelRes),
                        color = Color(0xFF8FA3BF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    val strokeWidth = 4.dp.toPx()
                                    drawLine(
                                        color = Color(0xFF49D4FF),
                                        start = Offset(0f, 0f),
                                        end = Offset(0f, size.height),
                                        strokeWidth = strokeWidth,
                                    )
                                }.background(Color(0x4008111F), RoundedCornerShape(8.dp))
                                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                    ) {
                        Text(
                            text = selectedText,
                            color = Color(0xFFDDE2F8),
                            fontSize = 14.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                // ── Textarea ───────────────────────────────────────
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            text = stringResource(hintRes),
                            color = Color(0xFF5C6A80),
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFDDE2F8),
                            unfocusedTextColor = Color(0xFFDDE2F8),
                            focusedContainerColor = Color(0xFF08111F),
                            unfocusedContainerColor = Color(0xFF08111F),
                            focusedBorderColor = Color(0x0894ADCE),
                            unfocusedBorderColor = Color(0x0894ADCE),
                            cursorColor = Color(0xFF49D4FF),
                        ),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── Action buttons ─────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.reader_cancel),
                            color = Color(0xFF8FA3BF),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onSave(text) },
                        shape = RoundedCornerShape(50),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF49D4FF),
                                contentColor = Color(0xFF08111F),
                            ),
                    ) {
                        Text(
                            text = stringResource(R.string.reader_save),
                            color = Color(0xFF08111F),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HighlightAnnotationModalDarkPreview() {
    NexoTheme(darkTheme = true) {
        HighlightAnnotationModal(
            titleRes = R.string.comment_modal_title,
            hintRes = R.string.annotation_textarea_comment_hint,
            snippetLabelRes = R.string.annotation_snippet_label,
            selectedText = "It was the best of times, it was the worst of times, it was the age of wisdom.",
            initialText = "",
            onSave = {},
            onDismiss = {},
        )
    }
}

// Preview-only: fixed dark palette — light render is intentionally broken (see sdd/ui-previews-both-themes spec R7; color migration deferred)
@Preview(showBackground = true)
@Composable
private fun HighlightAnnotationModalLightPreview() {
    NexoTheme(darkTheme = false) {
        HighlightAnnotationModal(
            titleRes = R.string.comment_modal_title,
            hintRes = R.string.annotation_textarea_comment_hint,
            snippetLabelRes = R.string.annotation_snippet_label,
            selectedText = "It was the best of times, it was the worst of times, it was the age of wisdom.",
            initialText = "",
            onSave = {},
            onDismiss = {},
        )
    }
}

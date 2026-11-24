package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.AccentOrange
import com.nexo.ui.theme.NexoTheme

/**
 * What a tap on a link inside the book opens, instead of jumping straight away:
 * the note's text right here when there is one, and the choice to go there or
 * stay. A stray touch on a tiny `[3]` now costs a swipe down, not a lost place.
 *
 * @param preview The note's text, or null when there is none (a plain link, or
 *   a note the book does not mark and whose target holds no text).
 * @param isLoading True while the note's text is being read from the book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLinkSheet(
    preview: String?,
    isLoading: Boolean,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = HandleColor) },
    ) {
        NoteLinkContent(preview = preview, isLoading = isLoading, onGo = onGo, onDismiss = onDismiss)
    }
}

@Composable
private fun NoteLinkContent(
    preview: String?,
    isLoading: Boolean,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNote = preview != null
    Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(AccentOrange.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isNote || isLoading) NexoIcons.Quote else NexoIcons.BookOpen,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(if (isNote || isLoading) R.string.reader_note_title else R.string.reader_link_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp)) {
            when {
                isLoading ->
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterStart).size(22.dp),
                        strokeWidth = 2.dp,
                        color = AccentOrange,
                    )
                preview != null ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                else ->
                    Text(
                        text = stringResource(R.string.reader_link_no_preview),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = TextSecondary,
                    )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FieldBackground, contentColor = TextPrimary),
            ) {
                Text(text = stringResource(R.string.reader_note_close), fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onGo,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = InkOnOrange),
            ) {
                Text(
                    text = stringResource(if (isNote || isLoading) R.string.reader_note_go else R.string.reader_link_go),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

// Reader chrome palette (fixed dark).
private val SheetBackground = Color(0xFF161F33)
private val FieldBackground = Color(0xFF1E2840)
private val HandleColor = Color(0xFF4A5568)
private val TextPrimary = Color(0xFFDDE2F8)
private val TextSecondary = Color(0xFF8A94AD)
private val InkOnOrange = Color(0xFF0B1120)

@Preview(showBackground = true, backgroundColor = 0xFF161F33)
@Composable
private fun NoteLinkContentPreview() {
    NexoTheme(darkTheme = true) {
        NoteLinkContent(
            preview = "3. «El extranjero» se publicó en 1942, el mismo año que «El mito de Sísifo».",
            isLoading = false,
            onGo = {},
            onDismiss = {},
        )
    }
}

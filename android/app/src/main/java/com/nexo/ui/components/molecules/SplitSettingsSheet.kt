package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.ReaderSettings
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.reader.settings.sections.ExpandablesSection
import com.nexo.ui.components.molecules.reader.settings.sections.FontSelectorSection
import com.nexo.ui.components.molecules.reader.settings.sections.FontSizeSection
import com.nexo.ui.components.molecules.reader.settings.sections.PreviewSection
import com.nexo.ui.components.molecules.reader.settings.sections.ThemeCirclesSection
import com.nexo.ui.icons.NexoIcons

/**
 * Orchestrator for split reader settings. Owns single shadow 12dp / 24dp
 * top corners and 0.4 / 0.6 weight split with straight #191F2F edge.
 * Delegates content to stateless sections — no local mutable config.
 */
@Composable
fun SplitSettingsSheet(
    settings: ReaderSettings,
    previewText: String,
    onSettingsChanged: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
    ) {
        // Preview 0.4
        PreviewSection(
            settings = settings,
            previewText = previewText,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
        )

        // Config 0.6 — straight edge, scrollable inside weight
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
                    .background(color = Color(0xFF191F2F))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4A5568))
                        .align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_typography),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFDDE2F8),
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = NexoIcons.Close,
                        contentDescription = stringResource(R.string.aa_close_settings),
                        tint = Color(0xFF718096),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            FontSelectorSection(settings = settings, onSettingsChanged = onSettingsChanged)

            Spacer(modifier = Modifier.height(16.dp))

            ThemeCirclesSection(settings = settings, onSettingsChanged = onSettingsChanged)

            Spacer(modifier = Modifier.height(20.dp))

            FontSizeSection(settings = settings, onSettingsChanged = onSettingsChanged)

            Spacer(modifier = Modifier.height(16.dp))

            ExpandablesSection(settings = settings, onSettingsChanged = onSettingsChanged)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SplitSettingsSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        SplitSettingsSheet(
            settings = ReaderSettings(),
            previewText = "The quick brown fox jumps over the lazy dog.",
            onSettingsChanged = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SplitSettingsSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        SplitSettingsSheet(
            settings = ReaderSettings(),
            previewText = "The quick brown fox jumps over the lazy dog.",
            onSettingsChanged = {},
            onDismiss = {},
        )
    }
}

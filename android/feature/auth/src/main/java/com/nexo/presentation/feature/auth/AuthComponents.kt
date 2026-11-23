package com.nexo.presentation.feature.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexo.domain.auth.AuthUiState
import com.nexo.feature.auth.BuildConfig
import com.nexo.feature.auth.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.tags.AuthTags
import com.nexo.ui.util.safePainterResource

/**
 * Auth layout: the bookshelf art fills the top of the screen untouched, and the
 * form sits on a rounded sheet anchored to the bottom.
 *
 * The art keeps its shelves in the upper-right quadrant; the old layout centred
 * the logo and title over the whole screen, so they landed on the books and the
 * text lost its contrast. Splitting art and form keeps both legible in either
 * theme. The compact lockup lives top-left, in the art's empty corner.
 *
 * The column is at least one screen tall, with a weighted spacer above the sheet:
 * short content pins the sheet to the bottom, long content (the expanded email
 * form, Register) pushes the art up and the whole screen scrolls.
 */
@Composable
fun AuthScreenScaffold(
    showBackArrow: Boolean,
    onNavigateBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val entered = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entered.animateTo(1f, tween(AuthEnterMs, easing = FastOutSlowInEasing)) }
    val sheetShape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        val screenHeight = maxHeight
        Image(
            painter =
                safePainterResource(
                    resId =
                        if (isDarkBackground) {
                            R.drawable.bg_auth_bookshelf_dark
                        } else {
                            R.drawable.bg_auth_bookshelf_light
                        },
                    fallbackColor = MaterialTheme.colorScheme.background,
                    source = "auth_background",
                ),
            contentDescription = null,
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The art settles from a slight zoom as the sheet rises.
                        val scale = 1f + ArtZoom * (1f - entered.value)
                        scaleX = scale
                        scaleY = scale
                    },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = screenHeight)) {
                AuthTopBar(
                    showBackArrow = showBackArrow,
                    onNavigateBack = onNavigateBack,
                    modifier = Modifier.graphicsLayer { alpha = entered.value },
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(ArtMinVisible))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = (1f - entered.value) * SheetRise.toPx()
                                alpha = entered.value
                            }.shadow(elevation = 24.dp, shape = sheetShape, clip = false)
                            .clip(sheetShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun AuthTopBar(
    showBackArrow: Boolean,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBackArrow) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(onClick = onNavigateBack)
                        .testTag(AuthTags.BACK),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        AuthLogo()
    }
}

/** Compact NEXO lockup, sized for the top-left corner of the auth art. */
@Composable
fun AuthLogo(modifier: Modifier = Modifier) {
    // Same dark-background signal AuthScreenScaffold uses to pick its bookshelf
    // art, so the lockup export always matches the surface behind it.
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Each export has its own canvas; the ratio comes from the vector actually drawn
    // so the lockup never gets stretched or letterboxed.
    val vector = if (isDarkBackground) NexoBrandLockup.Dark else NexoBrandLockup.Light
    Icon(
        imageVector = vector,
        contentDescription = stringResource(R.string.app_name),
        modifier =
            modifier
                .width(LockupWidth)
                .aspectRatio(vector.viewportWidth / vector.viewportHeight),
        tint = Color.Unspecified,
    )
}

private const val AuthEnterMs = 520
private const val ArtZoom = 0.06f
private val SheetCorner = 28.dp
private val SheetRise = 72.dp
private val LockupWidth = 128.dp

/** Height of art always left visible above the sheet, so the shelves never vanish. */
private val ArtMinVisible = 220.dp

@Composable
fun AuthOrDivider(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
fun AuthErrorText(uiState: AuthUiState) {
    uiState.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text =
                authFailureMessageTemplateRes(uiState.failureKind)
                    ?.let { messageTemplateRes -> stringResource(messageTemplateRes, error) }
                    ?: error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(AuthTags.ERROR),
        )
    }
}

@Composable
fun AuthFooterLink(
    prefix: String,
    link: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(
                text = link,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun AuthConfigError(uiState: com.nexo.domain.auth.AuthUiState) {
    if (!uiState.isConfigured) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.auth_config_error_google_unavailable),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
    } else {
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AuthDevBypass(onContinueLocal: () -> Unit) {
    if (BuildConfig.DEBUG) {
        Spacer(modifier = Modifier.height(16.dp))
        com.nexo.ui.components.atoms.NexoButton(
            onClick = onContinueLocal,
            variant = com.nexo.ui.components.atoms.NexoButtonVariant.TEXT,
            modifier = Modifier.testTag(AuthTags.DEV_BYPASS),
        ) {
            Icon(
                imageVector = NexoIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auth_continue_local_dev),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

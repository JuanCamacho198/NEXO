package com.nexo.presentation.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.nexo.domain.auth.AuthUiState
import com.nexo.feature.auth.BuildConfig
import com.nexo.feature.auth.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.tags.AuthTags
import com.nexo.ui.theme.NexoTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Auth layout, one look in every theme: a light-sky canvas with the NEXO hero
 * centred in the free space, and the form flowing straight below it.
 *
 * The auth flow ignores the system light/dark setting on purpose. It is the
 * brand's first impression, so it always wears the brand palette (sky canvas,
 * navy ink, orange accent) through [AuthBrandTheme]; the in-app theme applies
 * from Home onwards.
 *
 * The column is at least one screen tall. Two weighted spacers around the hero
 * absorb the free space, so short content (Login) centres the hero in the top
 * area and long content (Register, the expanded email form) shrinks that air to
 * nothing and scrolls, while the hero itself never collapses.
 */
@Composable
fun AuthScreenScaffold(
    showBackArrow: Boolean,
    onNavigateBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AuthBrandTheme {
        val entrance = rememberEntranceProgress()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            AuthBackground(modifier = Modifier.matchParentSize())
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = screenHeight)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AuthTopBar(showBackArrow = showBackArrow, onNavigateBack = onNavigateBack)
                    Spacer(modifier = Modifier.weight(1f))
                    AuthHero(
                        compact = showBackArrow,
                        modifier = Modifier.padding(vertical = 16.dp).staggeredEnter(entrance, 0),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .staggeredEnter(entrance, 1),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * The brand palette for the auth flow, independent of the system theme.
 *
 * Starts from the light NEXO theme so typography, shapes and the light token
 * set carry over, then overrides the slots the auth screens read: orange as
 * `primary` (filled actions, focused fields) with navy ink on it, white fields
 * on the sky canvas. Status-bar icons are forced dark while it is shown, since
 * the canvas is light even when the system runs dark.
 */
@Composable
private fun AuthBrandTheme(content: @Composable () -> Unit) {
    LightStatusBarIcons()
    NexoTheme(darkTheme = false) {
        MaterialTheme(
            colorScheme =
                MaterialTheme.colorScheme.copy(
                    primary = AuthOrange,
                    onPrimary = AuthInk,
                    background = AuthSkyTop,
                    onBackground = AuthInk,
                    surface = Color.White,
                    onSurface = AuthInk,
                    surfaceVariant = AuthSkyBottom,
                    onSurfaceVariant = AuthInkSecondary,
                    outline = AuthInk.copy(alpha = 0.22f),
                    outlineVariant = AuthInk.copy(alpha = 0.12f),
                ),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = true
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun AuthTopBar(
    showBackArrow: Boolean,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackArrow) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onNavigateBack)
                        .testTag(AuthTags.BACK),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = AuthInk,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The auth canvas: a sky gradient with four large, soft colour fields over it
 * (blue, the brand orange behind the hero, lilac, peach), the way a mesh
 * gradient reads, so the screen has colour instead of a flat near-white.
 *
 * The fields fade to transparent at their edges and stay light where the form
 * sits, so text and white buttons keep their contrast. They drift a few dp on a
 * slow loop; with system animations off they hold still.
 */
@Composable
private fun AuthBackground(modifier: Modifier = Modifier) {
    val animate = rememberSystemAnimationsEnabled()
    val drift by rememberInfiniteTransition(label = "authBackground").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(BackgroundDriftMs, easing = LinearEasing), RepeatMode.Restart),
        label = "authBackgroundDrift",
    )
    Canvas(modifier = modifier) {
        drawRect(Brush.verticalGradient(0f to AuthSkyTop, 0.45f to AuthSkyMid, 1f to AuthSkyBottom))
        val reach = BackgroundDriftDistance.toPx()
        BackgroundFields.forEach { field ->
            val angle = drift + field.phase
            val center =
                Offset(
                    x = size.width * field.x + if (animate) cos(angle) * reach else 0f,
                    y = size.height * field.y + if (animate) sin(angle) * reach else 0f,
                )
            val radius = size.width * field.radius
            drawCircle(
                brush = Brush.radialGradient(listOf(field.color, field.color.copy(alpha = 0f)), center = center, radius = radius),
                radius = radius,
                center = center,
            )
        }
    }
}

/** One soft colour field: centre as a fraction of the screen, radius as a fraction of its width. */
private data class ColorField(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
    val phase: Float,
)

private val BackgroundFields =
    listOf(
        // Blue, top-left: deepens the sky above the hero.
        ColorField(x = 0.05f, y = 0.10f, radius = 0.95f, color = Color(0x737FA8FF), phase = 0f),
        // Brand orange, right of the hero: warms the logo's side of the screen.
        ColorField(x = 1.0f, y = 0.30f, radius = 0.75f, color = Color(0x61FF9A52), phase = 1.6f),
        // Lilac, left, mid-height: links the blue and the orange.
        ColorField(x = 0.0f, y = 0.62f, radius = 0.70f, color = Color(0x4DB4A2FF), phase = 3.2f),
        // Peach, bottom-right: a warm floor under the footer, kept light for the text.
        ColorField(x = 0.95f, y = 1.0f, radius = 0.80f, color = Color(0x59FFC294), phase = 4.7f),
    )

private const val BackgroundDriftMs = 14000
private val BackgroundDriftDistance = 18.dp

/**
 * Whether the system plays animations. Off (animator duration scale 0) is how
 * users ask for less motion, so looping decoration holds still.
 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * The hero: the NEXO lockup floating on a breathing orange glow, with a few
 * sparkles twinkling around it (the same four-point stars as the brand art).
 *
 * All motion is one infinite transition and drawn in a single Canvas, so the
 * loop costs one layer. It holds still when the system has animations turned
 * off (animator duration scale 0), which users set for motion sensitivity.
 */
@Composable
private fun AuthHero(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val heroSize = if (compact) 200.dp else 280.dp
    val animate = rememberSystemAnimationsEnabled()
    val transition = rememberInfiniteTransition(label = "authHero")
    val float by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HeroFloatMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "heroFloat",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HeroBreatheMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "heroBreathe",
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HeroTwinkleMs, easing = LinearEasing), RepeatMode.Restart),
        label = "heroTwinkle",
    )
    Box(modifier = modifier.size(heroSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val glowAlpha = if (animate) HeroGlowMin + (HeroGlowMax - HeroGlowMin) * breathe else HeroGlowMax
            drawCircle(
                brush = Brush.radialGradient(listOf(AuthOrange.copy(alpha = glowAlpha), AuthOrange.copy(alpha = 0f))),
            )
            HeroSparkles.forEach { sparkle ->
                // Each sparkle runs the shared clock from its own phase: it swells
                // in and fades out once per cycle, so they never blink in unison.
                val local = if (animate) (twinkle + sparkle.phase) % 1f else SparkleRestPhase
                val pulse = sin(local * PI.toFloat())
                val radius = sparkle.radius.toPx() * (SparkleMinScale + (1f - SparkleMinScale) * pulse)
                drawSparkle(
                    center = Offset(size.width * sparkle.x, size.height * sparkle.y),
                    radius = radius,
                    alpha = SparkleMinAlpha + (1f - SparkleMinAlpha) * pulse,
                )
            }
        }
        AuthLogo(
            width = if (compact) 140.dp else 200.dp,
            modifier = Modifier.graphicsLayer { translationY = if (animate) float * HeroFloatDistance.toPx() else 0f },
        )
    }
}

/** A four-point sparkle: four curved arms meeting at the centre, like the stars in the brand art. */
private fun DrawScope.drawSparkle(
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    val path =
        Path().apply {
            moveTo(center.x, center.y - radius)
            quadraticTo(center.x, center.y, center.x + radius, center.y)
            quadraticTo(center.x, center.y, center.x, center.y + radius)
            quadraticTo(center.x, center.y, center.x - radius, center.y)
            quadraticTo(center.x, center.y, center.x, center.y - radius)
            close()
        }
    drawPath(path = path, color = AuthOrange.copy(alpha = alpha))
}

/** Sparkle position (fraction of the hero box), size and phase on the shared clock. */
private data class HeroSparkle(
    val x: Float,
    val y: Float,
    val radius: Dp,
    val phase: Float,
)

// Placed in the corners the wide lockup leaves empty, clear of the wordmark.
private val HeroSparkles =
    listOf(
        HeroSparkle(x = 0.17f, y = 0.27f, radius = 9.dp, phase = 0f),
        HeroSparkle(x = 0.84f, y = 0.24f, radius = 7.dp, phase = 0.35f),
        HeroSparkle(x = 0.88f, y = 0.72f, radius = 6.dp, phase = 0.65f),
        HeroSparkle(x = 0.13f, y = 0.74f, radius = 5.dp, phase = 0.5f),
        HeroSparkle(x = 0.6f, y = 0.13f, radius = 4.dp, phase = 0.82f),
    )

private const val HeroFloatMs = 3200
private const val HeroBreatheMs = 2600
private const val HeroTwinkleMs = 2800
private val HeroFloatDistance = 4.dp
private const val HeroGlowMin = 0.14f
private const val HeroGlowMax = 0.26f
private const val SparkleMinScale = 0.35f
private const val SparkleMinAlpha = 0.25f

/** Where a sparkle sits on its cycle when motion is off: near full, so the still frame still sparkles. */
private const val SparkleRestPhase = 0.45f

/** NEXO lockup: orange mark, navy wordmark (the export made for light canvases). */
@Composable
fun AuthLogo(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
) {
    val vector = NexoBrandLockup.Light
    Icon(
        imageVector = vector,
        contentDescription = stringResource(R.string.app_name),
        modifier =
            modifier
                .width(width)
                .aspectRatio(vector.viewportWidth / vector.viewportHeight),
        tint = Color.Unspecified,
    )
}

/**
 * Sky canvas: saturated at the top where the hero sits, paling towards the
 * form so the navy text and white buttons keep their contrast.
 */
internal val AuthSkyTop = Color(0xFFC9DCFF)
internal val AuthSkyMid = Color(0xFFE0EBFF)
internal val AuthSkyBottom = Color(0xFFF2F6FF)

/** Navy ink: the wordmark colour of the light lockup, so text and logo match. */
internal val AuthInk = Color(0xFF0D1D3B)
internal val AuthInkSecondary = Color(0xFF4A5B7A)

/** Brand orange, the mid stop of the logo gradient. Filled actions wear it with [AuthInk]. */
internal val AuthOrange = Color(0xFFF7781A)

/**
 * Deeper orange for text links: [AuthOrange] reads at only ~2.4:1 on the sky
 * canvas, this step clears 4.5:1 for 14sp text.
 */
internal val AuthLinkOrange = Color(0xFFB8470A)

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
                fontWeight = FontWeight.Bold,
                color = AuthLinkOrange,
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

/**
 * Debug-only shortcut into a local session. Kept small and quiet: it is a
 * developer escape hatch, and at full size it read as a third sign-in option.
 */
@Composable
fun AuthDevBypass(onContinueLocal: () -> Unit) {
    if (BuildConfig.DEBUG) {
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onContinueLocal,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.testTag(AuthTags.DEV_BYPASS),
        ) {
            Icon(
                imageVector = NexoIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.auth_continue_local_dev),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "By continuing, you agree to the Terms and the Privacy Policy", with both
 * labels linking to the public pages on the NEXO site: the same policy the
 * Google consent screen points at.
 *
 * The sentence is one format string with the labels as arguments, so each
 * language keeps its own word order; the links are attached by locating the
 * label text inside the formatted sentence.
 */
@Composable
fun AuthLegalNotice(modifier: Modifier = Modifier) {
    val terms = stringResource(R.string.auth_legal_terms)
    val privacy = stringResource(R.string.auth_legal_privacy)
    val sentence = stringResource(R.string.auth_legal_notice, terms, privacy)
    val termsUrl = stringResource(R.string.auth_legal_terms_url)
    val privacyUrl = stringResource(R.string.auth_legal_privacy_url)
    val linkStyles = TextLinkStyles(style = SpanStyle(color = AuthLinkOrange, fontWeight = FontWeight.SemiBold))
    val text =
        buildAnnotatedString {
            append(sentence)
            listOf(terms to termsUrl, privacy to privacyUrl).forEach { (label, url) ->
                val start = sentence.indexOf(label)
                if (start >= 0) addLink(LinkAnnotation.Url(url, linkStyles), start, start + label.length)
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

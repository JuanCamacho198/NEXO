package com.nexo.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.nexo.R
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.theme.NexoColors
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Onboarding daily-goal step (REQ-daily-reading-goal-2, SCEN-daily-reading-goal-1).
 *
 * Shown when no goal is stored or when re-entered from settings. Preselects the
 * option matching [initialMinutes]; [onSave] persists the choice and navigates
 * to Home. [onNavigateBack] wires the header back button when present.
 *
 * [padsSystemBars] is true when the screen owns the whole window (onboarding).
 * Inside Settings the host Scaffold already insets for the status bar and the
 * bottom navigation, so padding them again left a dead band above the header
 * and below the action.
 */
@Composable
fun OnboardingGoalScreen(
    onSave: (minutes: Int) -> Unit,
    modifier: Modifier = Modifier,
    initialMinutes: Int = DEFAULT_GOAL_MINUTES,
    onNavigateBack: (() -> Unit)? = null,
    padsSystemBars: Boolean = true,
) {
    val options = GOAL_OPTIONS
    val colors = nexoColors
    var selectedMinutes by rememberSaveable { mutableIntStateOf(initialMinutes) }
    val selected = options.firstOrNull { it.minutes == selectedMinutes }
    val entrance = rememberEntranceProgress()

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (padsSystemBars) Modifier.statusBarsPadding() else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = BOTTOM_ACTION_CLEARANCE),
        ) {
            GoalHeader(onNavigateBack = onNavigateBack)

            Spacer(modifier = Modifier.height(NexoDimens.spacingMd))

            GoalHero(
                valueRes = selected?.valueRes,
                accent = selected?.accentSelector?.invoke(colors) ?: colors.accentOrange,
                modifier = Modifier.staggeredEnter(entrance, 0),
            )

            Spacer(modifier = Modifier.height(NexoDimens.spacingLg))

            Column(modifier = Modifier.fillMaxWidth().staggeredEnter(entrance, 1)) {
                Text(
                    text = stringResource(R.string.onboarding_goal_hero_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_goal_hero_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NexoDimens.spacingMd),
                    color = colors.textSecondary,
                )
            }

            Spacer(modifier = Modifier.height(NexoDimens.spacingLg))

            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    GoalOptionCard(
                        option = option,
                        selected = selectedMinutes == option.minutes,
                        onSelect = { selectedMinutes = option.minutes },
                        modifier = Modifier.staggeredEnter(entrance, index + 2),
                    )
                }
            }
        }

        GoalBottomAction(
            onConfirm = { onSave(selectedMinutes) },
            padsSystemBars = padsSystemBars,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun GoalHeader(onNavigateBack: (() -> Unit)?) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onNavigateBack != null) {
            val backLabel = stringResource(R.string.nav_back)
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .clickable(onClickLabel = backLabel, onClick = onNavigateBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.ArrowBack,
                    contentDescription = backLabel,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.onboarding_goal_header),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = colors.textPrimary,
        )
    }
}

/**
 * The flame sits in a soft glow tinted by the chosen pace, and the pace's
 * "N min/day" pill underneath swaps in as the selection changes, so the hero
 * answers "what did I just pick" without reading the list again.
 */
@Composable
private fun GoalHero(
    valueRes: Int?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.fire_streak),
    )
    val glow by animateColorAsState(accent, tween(320), label = "goalGlow")
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }.background(
                        Brush.radialGradient(listOf(glow.copy(alpha = 0.28f), glow.copy(alpha = 0f))),
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.borderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                LottieAnimation(
                    composition = composition,
                    modifier = Modifier.size(68.dp),
                    iterations = LottieConstants.IterateForever,
                )
            }
        }
        if (valueRes != null) {
            AnimatedContent(
                targetState = valueRes,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically { -it / 2 })
                },
                label = "goalValue",
            ) { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(glow.copy(alpha = 0.16f))
                            .border(1.dp, glow.copy(alpha = 0.4f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GoalOptionCard(
    option: GoalOption,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val accent = option.accentSelector(colors)
    val shape = RoundedCornerShape(18.dp)
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.10f) else colors.surface,
        tween(220),
        label = "goalContainer",
    )
    val outline by animateColorAsState(
        if (selected) accent.copy(alpha = 0.85f) else colors.borderSubtle,
        tween(220),
        label = "goalOutline",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clip(shape)
                .background(container)
                .border(if (selected) 1.5.dp else 1.dp, outline, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = if (selected) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(option.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(option.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        Text(
            text = stringResource(option.valueRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.textPrimary else colors.textSecondary,
        )

        GoalRadio(selected = selected, accent = accent)
    }
}

/** Ring that fills with the option's accent; the check springs in on selection. */
@Composable
private fun GoalRadio(
    selected: Boolean,
    accent: Color,
) {
    val colors = nexoColors
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "goalCheck",
    )
    val fill by animateColorAsState(if (selected) accent else Color.Transparent, tween(180), label = "goalRadioFill")
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, if (selected) accent else colors.borderSubtle, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checkScale > 0.01f) {
            Icon(
                imageVector = NexoIcons.Check,
                contentDescription = stringResource(R.string.a11y_selected),
                // Dark ink on every accent: they are all light tints, so the page
                // colour would vanish on yellow/blue in the light theme.
                tint = Color.Black.copy(alpha = 0.8f),
                modifier =
                    Modifier
                        .size(15.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        },
            )
        }
    }
}

/**
 * Pinned action over a fade to the page colour, so the list scrolls under it
 * instead of ending at a hard tonal slab.
 */
@Composable
private fun GoalBottomAction(
    onConfirm: () -> Unit,
    padsSystemBars: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to colors.background.copy(alpha = 0f),
                        0.28f to colors.background,
                        1f to colors.background,
                    ),
                ).then(if (padsSystemBars) Modifier.navigationBarsPadding() else Modifier)
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = NexoDimens.spacingMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NexoButton(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_goal_action),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_goal_footer_note),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// `internal` (not `private`) so unit tests can assert the option model fields.
internal data class GoalOption(
    val minutes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
    val valueRes: Int,
    val icon: ImageVector,
    val accentSelector: NexoColors.() -> Color,
)

/** Light-blue accent for the "Serious" goal option (design token #B6C4FF). */
private val AccentLightBlue = Color(0xFFB6C4FF)

// `internal` (not `private`) so unit tests can assert the fixed option set.
//
// Accents are held as [NexoColors] selectors, not resolved values: this list is
// a file-level property read both by Compose and by JVM unit tests, so it must
// not capture a palette at class-init time. [OnboardingGoalScreen] resolves them
// against the active `nexoColors`.
internal val GOAL_OPTIONS =
    listOf(
        GoalOption(
            minutes = 10,
            titleRes = R.string.onboarding_goal_option_relaxed,
            descriptionRes = R.string.onboarding_goal_option_relaxed_desc,
            valueRes = R.string.onboarding_goal_option_relaxed_value,
            icon = NexoIcons.Spa,
            accentSelector = { accentGreen },
        ),
        GoalOption(
            minutes = 20,
            titleRes = R.string.onboarding_goal_option_regular,
            descriptionRes = R.string.onboarding_goal_option_regular_desc,
            valueRes = R.string.onboarding_goal_option_regular_value,
            icon = NexoIcons.AutoStories,
            accentSelector = { accentYellow },
        ),
        GoalOption(
            minutes = 30,
            titleRes = R.string.onboarding_goal_option_serious,
            descriptionRes = R.string.onboarding_goal_option_serious_desc,
            valueRes = R.string.onboarding_goal_option_serious_value,
            icon = NexoIcons.Book,
            accentSelector = { AccentLightBlue },
        ),
        GoalOption(
            minutes = 45,
            titleRes = R.string.onboarding_goal_option_intense,
            descriptionRes = R.string.onboarding_goal_option_intense_desc,
            valueRes = R.string.onboarding_goal_option_intense_value,
            icon = NexoIcons.Flame,
            accentSelector = { accentPurple },
        ),
    )

/** Extra bottom padding so scroll content clears the pinned action. */
private val BOTTOM_ACTION_CLEARANCE = 150.dp
private const val DEFAULT_GOAL_MINUTES = 30

@Preview(showBackground = true)
@Composable
private fun OnboardingGoalScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        OnboardingGoalScreen(
            onSave = {},
            onNavigateBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingGoalScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        OnboardingGoalScreen(
            onSave = {},
            onNavigateBack = {},
        )
    }
}

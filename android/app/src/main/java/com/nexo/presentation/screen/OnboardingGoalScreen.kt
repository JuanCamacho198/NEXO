package com.nexo.presentation.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.icons.NexoIcons

/**
 * Onboarding daily-goal step (REQ-daily-reading-goal-2, SCEN-daily-reading-goal-1).
 *
 * Shown when no goal is stored or when re-entered from settings. Preselects the
 * option matching [initialMinutes]; [onSave] persists the choice and navigates
 * to Home. [onNavigateBack] wires the header back button when present.
 */
@Composable
fun OnboardingGoalScreen(
    onSave: (minutes: Int) -> Unit,
    modifier: Modifier = Modifier,
    initialMinutes: Int = DEFAULT_GOAL_MINUTES,
    onNavigateBack: (() -> Unit)? = null,
) {
    val options = GOAL_OPTIONS
    var selectedMinutes by rememberSaveable { mutableIntStateOf(initialMinutes) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = NexoDimens.spacingLg,
                        end = NexoDimens.spacingLg,
                        bottom = BOTTOM_ACTION_CLEARANCE,
                    ),
        ) {
            GoalHeader(onNavigateBack = onNavigateBack)

            Spacer(modifier = Modifier.height(NexoDimens.spacingLg))

            GoalHero()

            Spacer(modifier = Modifier.height(NexoDimens.spacingXl))

            Text(
                text = stringResource(R.string.onboarding_goal_hero_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(NexoDimens.spacingSm))

            Text(
                text = stringResource(R.string.onboarding_goal_hero_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(NexoDimens.spacingXl))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                options.forEach { option ->
                    GoalOptionCard(
                        option = option,
                        selected = selectedMinutes == option.minutes,
                        onSelect = { selectedMinutes = option.minutes },
                    )
                }
            }
        }

        GoalBottomAction(
            onConfirm = { onSave(selectedMinutes) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun GoalHeader(onNavigateBack: (() -> Unit)?) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = NexoDimens.spacingMd, bottom = NexoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateBack != null) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = NexoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(NexoDimens.spacingSm))
        }
        Text(
            text = stringResource(R.string.onboarding_goal_header),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun GoalHero() {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.fire_streak),
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(96.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(NexoDimens.spacingMd),
                    ),
        ) {
            LottieAnimation(
                composition = composition,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp),
                iterations = LottieConstants.IterateForever,
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = NexoDimens.spacingXs, y = NexoDimens.spacingXs)
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(NexoDimens.spacingSm),
                        ).border(
                            width = 1.dp,
                            color = NexoColors.header,
                            shape = RoundedCornerShape(NexoDimens.spacingSm),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.Flame,
                    contentDescription = null,
                    tint = NexoColors.accentYellow,
                    modifier = Modifier.size(20.dp),
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
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        border =
            BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            color = option.accentColor.copy(alpha = 0.12f),
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = option.accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(NexoDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(option.titleRes),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(option.descriptionRes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(NexoDimens.spacingSm))

            Surface(
                shape = RoundedCornerShape(NexoDimens.spacingSm),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(option.valueRes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = NexoDimens.spacingSm,
                            vertical = NexoDimens.spacingXs,
                        ),
                )
            }

            Spacer(modifier = Modifier.width(NexoDimens.spacingSm))

            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(NexoDimens.spacingXs),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NexoIcons.Check,
                    contentDescription = null,
                    tint =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GoalBottomAction(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            NexoDivider(modifier = Modifier.fillMaxWidth())
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = NexoDimens.spacingLg,
                        vertical = NexoDimens.spacingMd,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NexoButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_goal_action),
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))

                Text(
                    text = stringResource(R.string.onboarding_goal_footer_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// `internal` (not `private`) so unit tests can assert the option model fields.
internal data class GoalOption(
    val minutes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
    val valueRes: Int,
    val icon: ImageVector,
    val accentColor: Color,
)

/** Light-blue accent for the "Serious" goal option (design token #B6C4FF). */
private val AccentLightBlue = Color(0xFFB6C4FF)

// `internal` (not `private`) so unit tests can assert the fixed option set.
internal val GOAL_OPTIONS =
    listOf(
        GoalOption(
            minutes = 10,
            titleRes = R.string.onboarding_goal_option_relaxed,
            descriptionRes = R.string.onboarding_goal_option_relaxed_desc,
            valueRes = R.string.onboarding_goal_option_relaxed_value,
            icon = NexoIcons.Spa,
            accentColor = NexoColors.accentGreen,
        ),
        GoalOption(
            minutes = 20,
            titleRes = R.string.onboarding_goal_option_regular,
            descriptionRes = R.string.onboarding_goal_option_regular_desc,
            valueRes = R.string.onboarding_goal_option_regular_value,
            icon = NexoIcons.AutoStories,
            accentColor = NexoColors.accentYellow,
        ),
        GoalOption(
            minutes = 30,
            titleRes = R.string.onboarding_goal_option_serious,
            descriptionRes = R.string.onboarding_goal_option_serious_desc,
            valueRes = R.string.onboarding_goal_option_serious_value,
            icon = NexoIcons.Book,
            accentColor = AccentLightBlue,
        ),
        GoalOption(
            minutes = 45,
            titleRes = R.string.onboarding_goal_option_intense,
            descriptionRes = R.string.onboarding_goal_option_intense_desc,
            valueRes = R.string.onboarding_goal_option_intense_value,
            icon = NexoIcons.Flame,
            accentColor = NexoColors.accentPurple,
        ),
    )

/** Extra bottom padding so scroll content clears the pinned action bar + system nav bar. */
private val BOTTOM_ACTION_CLEARANCE = 180.dp
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

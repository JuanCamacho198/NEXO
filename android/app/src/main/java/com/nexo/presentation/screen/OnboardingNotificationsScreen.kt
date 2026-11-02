package com.nexo.presentation.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.icons.NexoIcons

/**
 * Post-onboarding notification opt-in (Duolingo pattern).
 *
 * The OS permission dialog is asked **here**, after the goal, not on app open:
 * the user is sold the benefit first ("don't lose your streak") and only then is
 * the system prompt fired. The screen is deliberately a *sell*, not a wall — the
 * skip action is always available.
 *
 * @param onEnable invoked when the user taps the primary CTA. Must launch the
 *   system permission dialog; the screen does not do it itself so the caller
 *   owns the launcher and the one-shot bookkeeping.
 * @param onSkip invoked for "Not now"; the caller records the opt-in as asked
 *   and moves on without ever showing the OS dialog.
 * @param blocked when true, the user previously denied and the OS will not show
 *   the dialog again (Android 13+ returns "denied" silently). The primary CTA
 *   becomes "Open system settings" instead of a request that cannot work.
 * @param onOpenSettings invoked by that settings action.
 */
@Composable
fun OnboardingNotificationsScreen(
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(NexoDimens.spacingXl))

            NotificationsHero()

            Spacer(modifier = Modifier.height(NexoDimens.spacingXl))

            Text(
                text = stringResource(R.string.onboarding_notifications_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(NexoDimens.spacingSm))

            Text(
                text = stringResource(R.string.onboarding_notifications_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(NexoDimens.spacingXl))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
            ) {
                NotificationBenefit(
                    icon = NexoIcons.Notifications,
                    text = stringResource(R.string.onboarding_notifications_benefit_reminders),
                )
                NotificationBenefit(
                    icon = NexoIcons.Flame,
                    text = stringResource(R.string.onboarding_notifications_benefit_streak),
                )
                NotificationBenefit(
                    icon = NexoIcons.Settings,
                    text = stringResource(R.string.onboarding_notifications_benefit_quiet),
                )
            }

            if (blocked) {
                Spacer(modifier = Modifier.height(NexoDimens.spacingLg))
                Text(
                    text = stringResource(R.string.onboarding_notifications_blocked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NotificationBottomAction(
            blocked = blocked,
            onEnable = onEnable,
            onSkip = onSkip,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun NotificationsHero() {
    Box(
        modifier =
            Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(NexoDimens.spacingMd),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NexoIcons.Notifications,
            contentDescription = null,
            tint = NexoColors.accentYellow,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun NotificationBenefit(
    icon: ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(NexoDimens.spacingMd))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun NotificationBottomAction(
    blocked: Boolean,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    onClick = if (blocked) onOpenSettings else onEnable,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (blocked) {
                                    R.string.onboarding_notifications_open_settings
                                } else {
                                    R.string.onboarding_notifications_enable
                                },
                            ),
                        fontWeight = FontWeight.Bold,
                    )
                }

                NexoButton(
                    onClick = onSkip,
                    variant = NexoButtonVariant.TEXT,
                ) {
                    Text(text = stringResource(R.string.onboarding_notifications_skip))
                }
            }
        }
    }
}

/** Opens the OS notification settings for this app (the settings deep-link row). */
internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Fallback when the per-app notification page is unavailable on the device. */
internal fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Extra bottom padding so scroll content clears the pinned action bar + system nav bar. */
private val BOTTOM_ACTION_CLEARANCE = 200.dp

@Preview(showBackground = true)
@Composable
private fun OnboardingNotificationsDarkPreview() {
    NexoTheme(darkTheme = true) {
        OnboardingNotificationsScreen(onEnable = {}, onSkip = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingNotificationsLightPreview() {
    NexoTheme(darkTheme = false) {
        OnboardingNotificationsScreen(onEnable = {}, onSkip = {})
    }
}

package com.nexo.presentation.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.nexo.R
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.icons.NexoIcons

@Composable
fun TodaySummarySection(
    minutesReadToday: Int,
    sessionsToday: Int,
    currentStreak: Int,
) {
    Column {
        Text(text = stringResource(R.string.home_today_summary_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
            StatCard(icon = NexoIcons.Clock, value = "$minutesReadToday", label = stringResource(R.string.home_minutes), modifier = Modifier.weight(1f))
            StatCard(icon = NexoIcons.ChartLine, value = "$sessionsToday", label = stringResource(R.string.home_sessions), modifier = Modifier.weight(1f))
            StreakStatCard(streakDays = currentStreak, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun StreakStatCard(
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    val isZero = streakDays == 0
    Surface(modifier = modifier, shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(NexoDimens.spacingMd), horizontalAlignment = Alignment.CenterHorizontally) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.fire_streak))
            LottieAnimation(composition = composition, modifier = Modifier.size(24.dp), iterations = LottieConstants.IterateForever)
            Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
            Text(text = "$streakDays", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (isZero) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface)
            Text(text = stringResource(R.string.home_streak_days), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(NexoDimens.spacingMd), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

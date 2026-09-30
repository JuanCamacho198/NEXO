package com.nexo.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.nexo.R
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.DailyReadingActivity
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.StatisticsUiState
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.components.atoms.NexoErrorState
import com.nexo.ui.components.atoms.NexoLoadingIndicator
import com.nexo.ui.components.molecules.NexoHeader
import com.nexo.ui.components.molecules.NexoSectionHeader
import com.nexo.ui.icons.NexoIcons
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun StatisticsScreen(
    contentPadding: PaddingValues,
    viewModel: StatisticsViewModel,
    authSession: AuthSession? = null,
    onOpenAccount: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
    ) {
        item {
            NexoHeader(
                title = stringResource(R.string.home_nexo_title),
                avatarImageUrl = authSession?.photoUrl,
                avatarInitials = authSession?.displayName?.take(2)?.uppercase() ?: "NP",
                onAvatarClick = onOpenAccount,
                avatarContentDescription = stringResource(R.string.home_avatar_content_description),
            )
        }

        item {
            Column {
                Text(
                    text = stringResource(R.string.statistics_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.statistics_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            uiState.isLoading -> {
                item {
                    NexoLoadingIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                    )
                }
            }

            uiState.errorMessage != null -> {
                item {
                    NexoErrorState(
                        title = stringResource(R.string.error_unknown),
                        message = uiState.errorMessage ?: stringResource(R.string.error_unknown),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                    )
                }
            }

            else -> {
                item {
                    StreakHeroSection(currentStreak = uiState.currentStreak)
                }

                item {
                    ReadingActivitySection(uiState.weeklyActivity)
                }

                item {
                    GoalsSection(uiState.goalProgress)
                }

                item {
                    FavoriteGenresSection(uiState.favoriteGenres)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(NexoDimens.spacingMd)) }
    }
}

@Composable
private fun StreakHeroSection(currentStreak: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NexoDimens.spacingMd),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.fire_streak))
            LottieAnimation(
                composition = composition,
                modifier = Modifier.size(48.dp),
                iterations = LottieConstants.IterateForever,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$currentStreak",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.statistics_current_streak),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.statistics_days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummarySection(uiState: StatisticsUiState) {
    Column {
        NexoSectionHeader(title = stringResource(R.string.statistics_summary))
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm),
        ) {
            SummaryCard(
                icon = NexoIcons.Clock,
                value = "${uiState.totalMinutesRead}",
                label = stringResource(R.string.statistics_total_reading_time),
                unit = stringResource(R.string.statistics_minutes),
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                icon = NexoIcons.Flame,
                value = "${uiState.currentStreak}",
                label = stringResource(R.string.statistics_current_streak),
                unit = stringResource(R.string.statistics_days),
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                icon = NexoIcons.Book,
                value = "${uiState.booksRead}",
                label = stringResource(R.string.statistics_books_read),
                unit = "",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    icon: ImageVector,
    value: String,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NexoDimens.spacingSm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(NexoDimens.spacingMd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadingActivitySection(weeklyActivity: List<DailyReadingActivity>) {
    Column {
        NexoSectionHeader(
            title = stringResource(R.string.statistics_reading_activity),
            actionLabel = stringResource(R.string.statistics_last_7_days),
            onActionClick = {},
        )
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(NexoDimens.spacingSm),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(NexoDimens.spacingMd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val maxMinutes = weeklyActivity.maxOfOrNull { it.minutesRead }?.coerceAtLeast(1) ?: 1
                val dateFormatter = SimpleDateFormat("EEE", Locale.getDefault())
                weeklyActivity.forEach { day ->
                    val heightFraction = day.minutesRead.toFloat() / maxMinutes
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(24.dp)
                                    .fillMaxHeight(fraction = heightFraction.coerceIn(0.05f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dateFormatter.format(java.util.Date(day.dateEpochMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalsSection(goalProgress: Float) {
    Column {
        NexoSectionHeader(title = stringResource(R.string.statistics_goals))
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(NexoDimens.spacingSm),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(NexoDimens.spacingMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(80.dp)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { goalProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.format_percent, (goalProgress * 100).toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.statistics_daily_goal),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.statistics_minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteGenresSection(genres: List<String>) {
    Column {
        NexoSectionHeader(title = stringResource(R.string.statistics_favorite_genres))
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        if (genres.isEmpty()) {
            NexoEmptyState(
                icon = NexoIcons.Statistics,
                title = stringResource(R.string.statistics_no_genres),
                subtitle = stringResource(R.string.statistics_no_genres_subtitle),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { genre ->
                    FilterChip(
                        selected = false,
                        onClick = { },
                        label = { Text(genre) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatisticsScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        StatisticsScreenPreviewContent()
    }
}

@Preview(showBackground = true)
@Composable
private fun StatisticsScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        StatisticsScreenPreviewContent()
    }
}

@Composable
private fun StatisticsScreenPreviewContent() {
    val uiState =
        StatisticsUiState(
            isLoading = false,
            totalMinutesRead = 1240,
            currentStreak = 6,
            booksRead = 12,
            weeklyActivity =
                List(7) { index ->
                    DailyReadingActivity(
                        dateEpochMillis = System.currentTimeMillis() - (6 - index) * 86_400_000L,
                        minutesRead = listOf(30, 45, 20, 60, 40, 25, 35)[index],
                    )
                },
            goalProgress = 0.8f,
            favoriteGenres = listOf("Sci-Fi", "Fantasy", "Non-fiction"),
        )
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
    ) {
        item {
            NexoHeader(
                title = stringResource(R.string.home_nexo_title),
                avatarImageUrl = null,
                avatarInitials = "NP",
                onAvatarClick = null,
                avatarContentDescription = stringResource(R.string.home_avatar_content_description),
            )
        }
        item {
            Column {
                Text(
                    text = stringResource(R.string.statistics_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.statistics_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { StreakHeroSection(uiState.currentStreak) }
        item { ReadingActivitySection(uiState.weeklyActivity) }
        item { GoalsSection(uiState.goalProgress) }
        item { FavoriteGenresSection(uiState.favoriteGenres) }
        item { Spacer(modifier = Modifier.height(NexoDimens.spacingMd)) }
    }
}

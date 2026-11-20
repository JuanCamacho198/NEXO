package com.nexo.presentation.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.nexo.presentation.viewmodel.StatisticsUiState
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.components.atoms.NexoErrorState
import com.nexo.ui.components.atoms.NexoLoadingIndicator
import com.nexo.ui.components.molecules.NexoTopAppBar
import com.nexo.ui.components.molecules.TopLevelVariant
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val CardShape = RoundedCornerShape(20.dp)
private val ScreenInset = 20.dp
private val ChartHeight = 140.dp
private val BarWidth = 22.dp

/** Bars and the goal ring grow from zero once, after the cards have faded in. */
private const val GrowDurationMs = 800
private const val GrowDelayMs = 200

/** Alpha of the non-today bars: same hue as today's, one step lighter. */
private const val PastBarAlpha = 0.45f

/**
 * Statistics: one streak-and-today hero, the last seven days as a bar chart, the
 * daily goal as a ring, and favourite genres.
 *
 * @param showHeader False when hosted inside a Settings sub-page, which already
 *   draws its own titled bar — otherwise the title appeared twice.
 */
@Composable
fun StatisticsScreen(
    contentPadding: PaddingValues,
    viewModel: StatisticsViewModel,
    authSession: AuthSession? = null,
    onOpenAccount: () -> Unit = {},
    showHeader: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StatisticsContent(
        uiState = uiState,
        contentPadding = contentPadding,
        authSession = authSession,
        onOpenAccount = onOpenAccount,
        showHeader = showHeader,
    )
}

@Composable
private fun StatisticsContent(
    uiState: StatisticsUiState,
    contentPadding: PaddingValues,
    authSession: AuthSession?,
    onOpenAccount: () -> Unit,
    showHeader: Boolean,
) {
    val entrance = rememberEntranceProgress(start = !uiState.isLoading)
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingLg),
        contentPadding = PaddingValues(bottom = NexoDimens.spacingLg),
    ) {
        if (showHeader) {
            item {
                NexoTopAppBar(
                    title = stringResource(R.string.statistics_title),
                    subtitle = stringResource(R.string.statistics_subtitle),
                    variant = TopLevelVariant.Home,
                    userInitial =
                        authSession
                            ?.displayName
                            ?.trim()
                            ?.take(1)
                            ?.uppercase()
                            ?.ifEmpty { null },
                    avatarImageUrl = authSession?.photoUrl,
                    avatarContentDescription = stringResource(R.string.home_avatar_content_description),
                    onAvatarClick = onOpenAccount,
                    backContentDescription = stringResource(R.string.nav_back),
                )
            }
        }

        when {
            uiState.isLoading -> {
                item {
                    NexoLoadingIndicator(modifier = Modifier.fillMaxWidth().height(200.dp))
                }
            }

            uiState.errorMessage != null -> {
                item {
                    NexoErrorState(
                        title = stringResource(R.string.error_unknown),
                        message = uiState.errorMessage ?: stringResource(R.string.error_unknown),
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
            }

            else -> {
                item {
                    StreakTodayHero(
                        currentStreak = uiState.currentStreak,
                        minutesToday = uiState.minutesReadToday,
                        sessionsToday = uiState.sessionsToday,
                        modifier = Modifier.padding(horizontal = ScreenInset).staggeredEnter(entrance, 0),
                    )
                }
                item {
                    ReadingActivitySection(
                        weeklyActivity = uiState.weeklyActivity,
                        modifier = Modifier.padding(horizontal = ScreenInset).staggeredEnter(entrance, 1),
                    )
                }
                item {
                    GoalsSection(
                        goalProgress = uiState.goalProgress,
                        minutesToday = uiState.minutesReadToday,
                        modifier = Modifier.padding(horizontal = ScreenInset).staggeredEnter(entrance, 2),
                    )
                }
                item {
                    FavoriteGenresSection(
                        genres = uiState.favoriteGenres,
                        modifier = Modifier.padding(horizontal = ScreenInset).staggeredEnter(entrance, 3),
                    )
                }
            }
        }
    }
}

/** A 0→1 clock for the chart and ring growth; plays once per screen visit. */
@Composable
private fun rememberGrowProgress(): () -> Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(GrowDurationMs, delayMillis = GrowDelayMs, easing = FastOutSlowInEasing))
    }
    return { progress.value }
}

@Composable
private fun SectionTitle(
    text: String,
    trailing: String? = null,
) {
    val colors = nexoColors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // A plain caption, not a link: it used to be a "View all"-style action
        // with an empty click handler.
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        }
    }
}

@Composable
private fun StatCardSurface(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    content: @Composable () -> Unit,
) {
    val colors = nexoColors
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardShape)
                .then(if (brush != null) Modifier.background(brush) else Modifier.background(colors.surface))
                .border(1.dp, colors.borderSubtle, CardShape),
    ) {
        content()
    }
}

/**
 * Hero: the streak large on the left, today's minutes and sessions on the right.
 * It replaces a three-card "today" row plus a separate streak card, which showed
 * the same streak twice.
 */
@Composable
private fun StreakTodayHero(
    currentStreak: Int,
    minutesToday: Int,
    sessionsToday: Int,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    StatCardSurface(
        modifier = modifier,
        brush = Brush.linearGradient(listOf(colors.accentOrange.copy(alpha = 0.22f), colors.surface)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1.1f)) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.fire_streak))
                LottieAnimation(composition = composition, modifier = Modifier.size(44.dp), iterations = LottieConstants.IterateForever)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = currentStreak.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.statistics_days),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.statistics_current_streak),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                )
            }
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.borderSubtle),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.home_today_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                TodayStat(icon = NexoIcons.Clock, value = minutesToday.toString(), label = stringResource(R.string.home_minutes))
                TodayStat(icon = NexoIcons.ChartLine, value = sessionsToday.toString(), label = stringResource(R.string.home_sessions))
            }
        }
    }
}

@Composable
private fun TodayStat(
    icon: ImageVector,
    value: String,
    label: String,
) {
    val colors = nexoColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentOrange.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.accentOrange, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

/**
 * Seven-day bar chart. One series, so one hue and no legend; today's bar is the
 * full-strength step and past days a lighter step of the same hue. Bars have a
 * 4dp rounded data end and a square base on the baseline. Only today's and the
 * longest day's values are labelled; tapping any bar reveals its own value.
 */
@Composable
private fun ReadingActivitySection(
    weeklyActivity: List<DailyReadingActivity>,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val grow = rememberGrowProgress()
    // Canonical reading-day keys are 00:00 UTC of the local day; format in UTC so
    // the weekday never shifts. Rebuilt when the configuration locale changes.
    val locales = LocalConfiguration.current.locales
    val dateFormatter = remember(locales) { statisticsDayLabelFormat(locales[0]) }
    val maxMinutes = weeklyActivity.maxOfOrNull { it.minutesRead }?.coerceAtLeast(1) ?: 1
    val maxIndex = weeklyActivity.indexOfFirst { it.minutesRead == maxMinutes }
    val todayIndex = weeklyActivity.lastIndex
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val minutesUnit = stringResource(R.string.statistics_minutes)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            text = stringResource(R.string.statistics_reading_activity),
            trailing = stringResource(R.string.statistics_last_7_days),
        )
        StatCardSurface {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(ChartHeight),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    weeklyActivity.forEachIndexed { index, day ->
                        val dayLabel = dateFormatter.format(java.util.Date(day.dateEpochMillis))
                        val fraction = (day.minutesRead.toFloat() / maxMinutes).coerceIn(0f, 1f)
                        val showValue = day.minutesRead > 0 && (index == todayIndex || index == maxIndex || index == selected)
                        val isToday = index == todayIndex
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { selected = if (selected == index) -1 else index }
                                    .semantics(mergeDescendants = true) { contentDescription = "$dayLabel ${day.minutesRead} $minutesUnit" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            if (showValue) {
                                Text(
                                    text = day.minutesRead.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isToday || index == selected) colors.textPrimary else colors.textSecondary,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            // Minimum sliver so an empty day still reads as a day on the axis.
                            Box(
                                modifier =
                                    Modifier
                                        .width(BarWidth)
                                        .fillMaxHeight(fraction = (fraction * grow()).coerceAtLeast(0.03f) * BarMaxShare)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (isToday || index == selected) colors.accentOrange else colors.accentOrange.copy(alpha = PastBarAlpha)),
                            )
                        }
                    }
                }
                // Recessive baseline.
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    weeklyActivity.forEachIndexed { index, day ->
                        Text(
                            text = dateFormatter.format(java.util.Date(day.dateEpochMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (index == todayIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == todayIndex) colors.textPrimary else colors.textSecondary,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Share of the chart height the tallest bar may take, leaving room for its label. */
private const val BarMaxShare = 0.82f

/**
 * Builds the day-label formatter for [locale].
 *
 * Keyed on the observable configuration locale by the caller so a locale
 * change rebuilds it; UTC keeps the weekday pinned to the canonical
 * reading-day key (00:00 UTC of the local day).
 */
internal fun statisticsDayLabelFormat(locale: Locale): SimpleDateFormat =
    SimpleDateFormat("EEE", locale).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

@Composable
private fun GoalsSection(
    goalProgress: Float,
    minutesToday: Int,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val grow = rememberGrowProgress()
    val progress = goalProgress.coerceIn(0f, 1f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.statistics_goals))
        StatCardSurface {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress * grow() },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 9.dp,
                        color = if (progress >= 1f) colors.accentGreen else colors.accentOrange,
                        trackColor = colors.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                    Text(
                        text = stringResource(R.string.format_percent, (progress * grow() * 100).toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.statistics_daily_goal),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    // Today's minutes give the ring a concrete number; the bare
                    // "Minutes" label used to stand alone with no value.
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = minutesToday.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.statistics_minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteGenresSection(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.statistics_favorite_genres))
        if (genres.isEmpty()) {
            StatCardSurface {
                NexoEmptyState(
                    icon = NexoIcons.Statistics,
                    title = stringResource(R.string.statistics_no_genres),
                    subtitle = stringResource(R.string.statistics_no_genres_subtitle),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        } else {
            // Wraps instead of a single Row that clipped every genre past the
            // screen edge. Static pills: these never had an action.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { genre ->
                    Row(
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(colors.surface)
                                .border(1.dp, colors.borderSubtle, CircleShape)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.accentOrange))
                        Text(text = genre, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
                    }
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
            minutesReadToday = 42,
            sessionsToday = 3,
        )
    StatisticsContent(
        uiState = uiState,
        contentPadding = PaddingValues(0.dp),
        authSession = null,
        onOpenAccount = {},
        showHeader = true,
    )
}

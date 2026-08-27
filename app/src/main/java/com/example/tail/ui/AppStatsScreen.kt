package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.applyDivider
import com.example.tail.data.invertedBinaryPoints
import com.example.tail.data.isInternalValueKey
import com.example.tail.data.effectiveEntriesWithFallback
import com.example.tail.data.effectivePointsWithFallback
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.parseDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── Color palette ─────────────────────────────────────────────────────────────
// The screen is rainbow-themed around the app's own colour progression
// (Red → Orange → Green → Blue → Pink → Yellow): each section's title and
// background take the next step of the progression, matching the vibe of
// the settings screen the stats are reached from.
private val SectionTitleColor = Color(0xFFFFD700)
private val LabelColor = Color(0xFFADD8E6)
private val ValueColor = Color.White
private val DimColor = Color(0xFF888888)
private val DateLinkColor = Color(0xFF66CCFF)
private val ClickableCountColor = Color(0xFF88FF88)
private val SectionBg = Color(0xFF1A1A2E)
private val DividerColor = Color(0xFF333344)
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val GoldValue = Color(0xFFFFD700)

/** Vivid title colour for the n-th section of the rainbow. */
private fun rainbowTitleColor(accentIndex: Int): Color =
    screenProgressionAccent(accentIndex)

/** Section background: the navy base whispered toward the progression hue. */
private fun rainbowSectionBg(accentIndex: Int): Color =
    lerp(SectionBg, screenProgressionColor(accentIndex), 0.35f)

private val DISPLAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d yyyy")

/**
 * Comprehensive App Stats screen showing aggregate statistics across all habits.
 * Dates are clickable links that navigate back to the main grid with that date selected.
 * Count values that represent habit lists are clickable to show a popup list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatsScreen(
    viewModel: HabitViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDate: (LocalDate) -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val dividers = settings.habitDividers

    val disabledHabits = settings.disabledHabits
    val garminHabits = settings.garminHabitLinks.keys

    // Stats-list exclusion toggles (settings popup, top-right gear icon)
    var excludeGarminFromLists by rememberSaveable { mutableStateOf(true) }
    var excludeDisabledFromLists by rememberSaveable { mutableStateOf(true) }
    var showListSettings by remember { mutableStateOf(false) }
    val noPointsHabits = settings.noPointsHabits
    val secondaryValueHabits = settings.secondaryValueHabits
    val secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits
    val timerMinutesPrimaryHabits = settings.widgetTimerMinutesPrimary
    val invertedBinaryHabits = settings.invertedBinaryHabits

    // Compute all stats from the cached database
    val db = viewModel.getCachedDatabase()
    val listExcludedHabits = buildSet {
        if (excludeGarminFromLists) addAll(garminHabits)
        if (excludeDisabledFromLists) addAll(disabledHabits)
    }
    val stats = remember(db, dividers, disabledHabits, noPointsHabits, secondaryValueHabits, secondaryValueFallbackHabits, timerMinutesPrimaryHabits, invertedBinaryHabits, listExcludedHabits) {
        computeAppStats(db, dividers, disabledHabits, noPointsHabits, secondaryValueHabits, secondaryValueFallbackHabits, timerMinutesPrimaryHabits, invertedBinaryHabits, listExcludedHabits)
    }

    // State for the habit-list popup
    var popupTitle by remember { mutableStateOf("") }
    var popupItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showPopup by remember { mutableStateOf(false) }

    // Expand/collapse state for the top-habits lists (show top 10 vs full list)
    var expandedTotalPoints by rememberSaveable { mutableStateOf(false) }
    var expandedLongestStreak by rememberSaveable { mutableStateOf(false) }
    var expandedCurrentStreak by rememberSaveable { mutableStateOf(false) }
    var expandedAntiStreak by rememberSaveable { mutableStateOf(false) }
    var expandedSingleDay by rememberSaveable { mutableStateOf(false) }

    // State for the streak graph popup — use rememberSaveable so it survives
    // the configuration change triggered by forcing landscape orientation.
    // We store a graph key (string) and derive data from stats on recomposition.
    var graphPopupKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun openPopup(title: String, items: List<Pair<String, String>>) {
        popupTitle = title
        popupItems = items
        showPopup = true
    }

    // Derive graph data from the key + stats
    data class GraphInfo(
        val title: String,
        val data: List<Pair<String, Int>>,
        val color: Color,
        val currentValue: Int?,
        val onValueClick: ((LocalDate, Int) -> Unit)? = null
    )

    val graphInfo: GraphInfo? = when (graphPopupKey) {
        "total_streak_days" -> GraphInfo(
            "Total Streak Days Over Time",
            stats.dailyTotalStreakDays,
            GreenValue,
            stats.totalStreakDays
        )
        "total_anti_streak_days" -> GraphInfo(
            "Total Anti-Streak Days Over Time",
            stats.dailyTotalAntiStreakDays,
            RedValue,
            stats.totalAntiStreakDays
        )
        "habits_with_streak" -> GraphInfo(
            "Habits With Streak Over Time",
            stats.dailyHabitsWithStreak,
            GreenValue,
            stats.habitsWithStreak
        )
        "habits_with_anti_streak" -> GraphInfo(
            "Habits With Anti-Streak Over Time",
            stats.dailyHabitsWithAntiStreak,
            RedValue,
            stats.habitsWithAntiStreak
        )
        "cumulative_points" -> GraphInfo(
            "Cumulative Habit Points Over Time",
            stats.dailyCumulativePoints,
            GoldValue,
            stats.totalPointsAllTime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        "today_points" -> GraphInfo(
            "Habits Done Per Day Over Time",
            stats.dailyHabitsDone,
            GreenValue,
            stats.dailyHabitsDone.lastOrNull()?.second,
            onValueClick = { date, count ->
                openPopup(
                    "Habits Done on $date ($count)",
                    stats.dailyHabitsDoneLists[com.example.tail.data.dateString(date)].orEmpty()
                )
            }
        )
        // Rolling-average graphs each take a colour from the app's rainbow
        // progression so the charts themselves carry the rainbow theme.
        "avg_last_7_days" -> GraphInfo(
            "7-Day Rolling Average Over Time",
            stats.dailyAvgLast7Days.map { Pair(it.first, it.second.toInt()) },
            screenProgressionAccent(3), // blue
            stats.avgLast7Days.toInt()
        )
        "avg_last_30_days" -> GraphInfo(
            "30-Day Rolling Average Over Time",
            stats.dailyAvgLast30Days.map { Pair(it.first, it.second.toInt()) },
            screenProgressionAccent(4), // pink
            stats.avgLast30Days.toInt()
        )
        "avg_last_90_days" -> GraphInfo(
            "90-Day Rolling Average Over Time",
            stats.dailyAvgLast90Days.map { Pair(it.first, it.second.toInt()) },
            screenProgressionAccent(5), // yellow
            stats.avgLast90Days.toInt()
        )
        "avg_last_365_days" -> GraphInfo(
            "365-Day Rolling Average Over Time",
            stats.dailyAvgLast365Days.map { Pair(it.first, it.second.toInt()) },
            screenProgressionAccent(2), // green
            stats.avgLast365Days.toInt()
        )
        "avg_all_time" -> GraphInfo(
            "All-Time Rolling Average Over Time",
            stats.dailyAvgAllTime.map { Pair(it.first, it.second.toInt()) },
            screenProgressionAccent(1), // orange
            stats.avgAllTime.toInt()
        )
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showListSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Stats list settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (db.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No habit data loaded yet.\nSelect a habitsdb.txt file in Settings.",
                        color = DimColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Overview ──────────────────────────────────────────────────
                StatsSection(title = "📊 Overview", accentIndex = 0) {
                    StatClickableCountRow(
                        label = "Total habits tracked",
                        count = stats.totalHabits,
                        onClick = {
                            openPopup(
                                "All Habits (${stats.totalHabits})",
                                stats.allHabitsList
                            )
                        }
                    )
                    if (stats.disabledHabitsCount > 0) {
                        StatRow(
                            label = "Disabled habits",
                            value = stats.disabledHabitsCount.toString(),
                            valueColor = Color(0xFFFF6666)
                        )
                    }
                    StatRow("Total days with data (>0 pts)", stats.totalDaysWithData.toString())
                    StatRow("Days since first entry", stats.daysSinceFirstEntry.toString())
                    StatDateRow("First day with data", stats.firstDayWithData, onNavigateToDate)
                    StatDateRow("Most recent day with data", stats.lastDayWithData, onNavigateToDate)
                    StatGraphableRow(
                        label = "Total habit points (all time)",
                        value = formatLargeNumber(stats.totalPointsAllTime),
                        valueColor = GoldValue,
                        onClick = { graphPopupKey = "cumulative_points" }
                    )
                    StatGraphableRow(
                        label = "Today's points",
                        value = stats.todayPoints.toString(),
                        valueColor = GreenValue,
                        onClick = { graphPopupKey = "today_points" }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(4.dp))
                    StatGraphableRow(
                        label = "Total streak days (all habits)",
                        value = stats.totalStreakDays.toString(),
                        valueColor = GreenValue,
                        onClick = { graphPopupKey = "total_streak_days" }
                    )
                    StatGraphableRow(
                        label = "Total anti-streak days (all habits)",
                        value = stats.totalAntiStreakDays.toString(),
                        valueColor = RedValue,
                        onClick = { graphPopupKey = "total_anti_streak_days" }
                    )
                    StatGraphableCountRow(
                        label = "Habits with active streak",
                        count = stats.habitsWithStreak,
                        valueColor = GreenValue,
                        onClickGraph = { graphPopupKey = "habits_with_streak" },
                        onClickList = {
                            openPopup(
                                "Habits With Streak (${stats.habitsWithStreak})",
                                stats.habitsWithStreakList
                            )
                        }
                    )
                    StatGraphableCountRow(
                        label = "Habits with active anti-streak",
                        count = stats.habitsWithAntiStreak,
                        valueColor = RedValue,
                        onClickGraph = { graphPopupKey = "habits_with_anti_streak" },
                        onClickList = {
                            openPopup(
                                "Habits With Anti-Streak (${stats.habitsWithAntiStreak})",
                                stats.habitsWithAntiStreakList
                            )
                        }
                    )
                }

                // ── Highest Points ────────────────────────────────────────────
                StatsSection(title = "🏆 Highest Points", accentIndex = 1) {
                    StatDateValueRow(
                        label = "Best single day",
                        value = stats.highestPointsDay.second.toString(),
                        date = stats.highestPointsDay.first,
                        onNavigateToDate = onNavigateToDate
                    )
                    StatDateValueRow(
                        label = "Best 7-day average",
                        value = "%.2f".format(stats.highestPointsWeek.second),
                        date = stats.highestPointsWeek.first,
                        onNavigateToDate = onNavigateToDate,
                        dateLabel = "(ending)"
                    )
                    StatDateValueRow(
                        label = "Best 30-day average",
                        value = "%.2f".format(stats.highestPointsMonth.second),
                        date = stats.highestPointsMonth.first,
                        onNavigateToDate = onNavigateToDate,
                        dateLabel = "(ending)"
                    )
                    StatDateValueRow(
                        label = "Best 90-day average",
                        value = "%.2f".format(stats.highestPoints90Days.second),
                        date = stats.highestPoints90Days.first,
                        onNavigateToDate = onNavigateToDate,
                        dateLabel = "(ending)"
                    )
                    StatDateValueRow(
                        label = "Best 365-day average",
                        value = "%.2f".format(stats.highestPoints365Days.second),
                        date = stats.highestPoints365Days.first,
                        onNavigateToDate = onNavigateToDate,
                        dateLabel = "(ending)"
                    )
                }

                // ── Daily Averages ────────────────────────────────────────────
                StatsSection(title = "📈 Daily Averages", accentIndex = 2) {
                    StatGraphableRow(
                        label = "Average (last 7 days)",
                        value = "%.2f".format(stats.avgLast7Days),
                        valueColor = screenProgressionAccent(3), // blue
                        onClick = { graphPopupKey = "avg_last_7_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 30 days)",
                        value = "%.2f".format(stats.avgLast30Days),
                        valueColor = screenProgressionAccent(4), // pink
                        onClick = { graphPopupKey = "avg_last_30_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 90 days)",
                        value = "%.2f".format(stats.avgLast90Days),
                        valueColor = screenProgressionAccent(5), // yellow
                        onClick = { graphPopupKey = "avg_last_90_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 365 days)",
                        value = "%.2f".format(stats.avgLast365Days),
                        valueColor = screenProgressionAccent(2), // green
                        onClick = { graphPopupKey = "avg_last_365_days" }
                    )
                    StatGraphableRow(
                        label = "Average (all time)",
                        value = "%.2f".format(stats.avgAllTime),
                        valueColor = screenProgressionAccent(1), // orange
                        onClick = { graphPopupKey = "avg_all_time" }
                    )
                }

                // ── Streaks (aggregate) ───────────────────────────────────────
                StatsSection(title = "🔥 Aggregate Streaks", accentIndex = 3) {
                    StatRow(
                        "Current streak",
                        formatStreakDays(stats.currentAggregateStreak),
                        valueColor = if (stats.currentAggregateStreak > 0) GreenValue else DimColor
                    )
                    StatRow(
                        "Longest streak",
                        formatStreakDays(stats.longestAggregateStreak),
                        valueColor = GoldValue
                    )
                    StatDateRow(
                        "Longest streak started",
                        stats.longestAggregateStreakStartDate,
                        onNavigateToDate
                    )
                    StatDateRow(
                        "Longest streak ended",
                        stats.longestAggregateStreakEndDate,
                        onNavigateToDate
                    )
                    StatRow(
                        "Current zero-day streak",
                        formatStreakDays(stats.currentZeroDayStreak),
                        valueColor = if (stats.currentZeroDayStreak > 0) RedValue else DimColor
                    )
                }

                // ── Top Habits by Total Points ────────────────────────────────
                StatsSection(title = "⭐ Top 10 Habits by Total Points", accentIndex = 4) {
                    val shown = if (expandedTotalPoints) stats.topHabitsByTotalPoints
                        else stats.topHabitsByTotalPoints.take(10)
                    shown.forEachIndexed { index, (name, points) ->
                        StatRow(
                            "${index + 1}. $name",
                            formatLargeNumber(points)
                        )
                    }
                    ExpandListToggle(
                        totalCount = stats.topHabitsByTotalPoints.size,
                        expanded = expandedTotalPoints,
                        onToggle = { expandedTotalPoints = !expandedTotalPoints }
                    )
                }

                // ── Top Habits by Longest Streak ──────────────────────────────
                StatsSection(title = "🔗 Top 10 Habits by Longest Streak", accentIndex = 5) {
                    val shown = if (expandedLongestStreak) stats.topHabitsByLongestStreak
                        else stats.topHabitsByLongestStreak.take(10)
                    shown.forEachIndexed { index, (name, streak, endDate) ->
                        StatDateValueRow(
                            label = "${index + 1}. $name",
                            value = "$streak days",
                            date = endDate,
                            onNavigateToDate = onNavigateToDate
                        )
                    }
                    ExpandListToggle(
                        totalCount = stats.topHabitsByLongestStreak.size,
                        expanded = expandedLongestStreak,
                        onToggle = { expandedLongestStreak = !expandedLongestStreak }
                    )
                }

                // ── Top Habits by Current Streak ──────────────────────────────
                StatsSection(title = "🏃 Top 10 Habits by Current Streak", accentIndex = 6) {
                    val shown = if (expandedCurrentStreak) stats.topHabitsByCurrentStreak
                        else stats.topHabitsByCurrentStreak.take(10)
                    shown.forEachIndexed { index, (name, streak) ->
                        StatRow(
                            "${index + 1}. $name",
                            "$streak days",
                            valueColor = when (index) {
                                0 -> GreenValue
                                1 -> Color(0xFF70EE70)
                                2 -> Color(0xFF60DD60)
                                else -> ValueColor
                            }
                        )
                    }
                    ExpandListToggle(
                        totalCount = stats.topHabitsByCurrentStreak.size,
                        expanded = expandedCurrentStreak,
                        onToggle = { expandedCurrentStreak = !expandedCurrentStreak }
                    )
                }

                // ── Worst Anti-Streaks ────────────────────────────────────────
                StatsSection(title = "💤 Top 10 Longest Current Anti-Streaks", accentIndex = 7) {
                    val shown = if (expandedAntiStreak) stats.topHabitsByAntiStreak
                        else stats.topHabitsByAntiStreak.take(10)
                    shown.forEachIndexed { index, (name, antiStreak) ->
                        StatRow(
                            "${index + 1}. $name",
                            "$antiStreak days",
                            valueColor = RedValue
                        )
                    }
                    ExpandListToggle(
                        totalCount = stats.topHabitsByAntiStreak.size,
                        expanded = expandedAntiStreak,
                        onToggle = { expandedAntiStreak = !expandedAntiStreak }
                    )
                }

                // ── Habits with Highest Single-Day Count ──────────────────────
                StatsSection(title = "💥 Highest Single-Day Count per Habit", accentIndex = 8) {
                    val shown = if (expandedSingleDay) stats.topHabitsBySingleDayHigh
                        else stats.topHabitsBySingleDayHigh.take(10)
                    shown.forEachIndexed { index, triple ->
                        StatDateValueRow(
                            label = "${index + 1}. ${triple.first}",
                            value = triple.second.toString(),
                            date = triple.third,
                            onNavigateToDate = onNavigateToDate
                        )
                    }
                    ExpandListToggle(
                        totalCount = stats.topHabitsBySingleDayHigh.size,
                        expanded = expandedSingleDay,
                        onToggle = { expandedSingleDay = !expandedSingleDay }
                    )
                }

                // ── Day of Week Analysis ──────────────────────────────────────
                StatsSection(
                    title = "📅 Average Points by Day of Week",
                    accentIndex = 9,
                    infoText = "Full weeks with zero points are excluded from these averages."
                ) {
                    val maxDow = stats.avgPointsByDayOfWeek.maxByOrNull { it.second }?.second
                    val minDow = stats.avgPointsByDayOfWeek.filter { it.second > 0 }
                        .minByOrNull { it.second }?.second
                    stats.avgPointsByDayOfWeek.forEach { (dayName, avg) ->
                        StatRow(
                            dayName,
                            "%.2f".format(avg),
                            valueColor = when (avg) {
                                maxDow -> GoldValue
                                minDow -> RedValue
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Day of Month Analysis ─────────────────────────────────────
                StatsSection(
                    title = "📅 Average Points by Day of Month",
                    accentIndex = 9,
                    infoText = "Full months with zero points are excluded from these averages."
                ) {
                    val maxDom = stats.avgPointsByDayOfMonth.maxByOrNull { it.second }?.second
                    val minDom = stats.avgPointsByDayOfMonth.filter { it.second > 0 }
                        .minByOrNull { it.second }?.second
                    stats.avgPointsByDayOfMonth.forEach { (dayLabel, avg) ->
                        StatRow(
                            dayLabel,
                            "%.2f".format(avg),
                            valueColor = when (avg) {
                                maxDom -> GoldValue
                                minDom -> RedValue
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Yearly Averages ───────────────────────────────────────────
                StatsSection(
                    title = "📅 Average Points by Year",
                    accentIndex = 9,
                    infoText = "Years with no data (or all-zero days) are excluded from this list."
                ) {
                    val maxYear = stats.avgPointsByYear.maxByOrNull { it.second }?.second
                    val minYear = stats.avgPointsByYear.filter { it.second > 0 }
                        .minByOrNull { it.second }?.second
                    stats.avgPointsByYear.forEach { (yearLabel, avg) ->
                        StatRow(
                            yearLabel,
                            "%.2f".format(avg),
                            valueColor = when (avg) {
                                maxYear -> GoldValue
                                minYear -> RedValue
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Monthly Trends ────────────────────────────────────────────
                StatsSection(title = "📆 Best Months (Total Points)", accentIndex = 10) {
                    stats.topMonths.forEachIndexed { index, (monthLabel, points) ->
                        StatRow(
                            "${index + 1}. $monthLabel",
                            formatLargeNumber(points)
                        )
                    }
                }

                // ── Milestones ────────────────────────────────────────────────
                StatsSection(title = "🎯 Milestones", accentIndex = 11) {
                    StatRow("Days with ≥1 point", stats.daysWithAtLeastOnePoint.toString())
                    StatRow("Days with zero points", stats.daysWithZeroPoints.toString())
                    StatRow(
                        "Completion rate",
                        "%.1f%%".format(stats.completionRate),
                        valueColor = when {
                            stats.completionRate >= 90 -> GoldValue
                            stats.completionRate >= 70 -> GreenValue
                            stats.completionRate >= 50 -> ValueColor
                            else -> RedValue
                        }
                    )
                    StatClickableCountRow(
                        label = "Habits done today",
                        count = stats.habitsDoneToday,
                        onClick = {
                            openPopup(
                                "Habits Done Today (${stats.habitsDoneToday})",
                                stats.habitsDoneTodayList
                            )
                        }
                    )
                    StatClickableCountRow(
                        label = "Habits NOT done today",
                        count = stats.habitsNotDoneToday,
                        onClick = {
                            openPopup(
                                "Habits Not Done Today (${stats.habitsNotDoneToday})",
                                stats.habitsNotDoneTodayList
                            )
                        }
                    )
                    StatDateRow(
                        "Most habits done in a single day",
                        stats.mostHabitsDoneInDayDate,
                        onNavigateToDate,
                        suffix = " (${stats.mostHabitsDoneInDayCount} habits)"
                    )
                    StatDateRow(
                        "Most points in a single day",
                        stats.highestPointsDay.first,
                        onNavigateToDate,
                        suffix = " (${stats.highestPointsDay.second} pts)"
                    )
                }

                // ── Habit Diversity ────────────────────────────────────────────
                StatsSection(title = "🌈 Habit Diversity", accentIndex = 12) {
                    StatRow("Habits with data today", "${stats.habitsDoneToday} / ${stats.totalHabits}")
                    StatClickableCountRow(
                        label = "Habits ever done (at least once)",
                        count = stats.habitsEverDone,
                        onClick = {
                            openPopup(
                                "Habits Ever Done (${stats.habitsEverDone})",
                                stats.habitsEverDoneList
                            )
                        }
                    )
                    StatClickableCountRow(
                        label = "Habits never done",
                        count = stats.habitsNeverDone,
                        onClick = {
                            openPopup(
                                "Habits Never Done (${stats.habitsNeverDone})",
                                stats.habitsNeverDoneList
                            )
                        }
                    )
                    StatRow("Average habits done per day", "%.1f".format(stats.avgHabitsDonePerDay))
                    StatClickableCountRow(
                        label = "Unique habits done today",
                        count = stats.uniqueHabitsToday,
                        onClick = {
                            openPopup(
                                "Unique Habits Done Today (${stats.uniqueHabitsToday})",
                                stats.uniqueHabitsTodayList
                            )
                        }
                    )
                    StatCountDateRow(
                        label = "Day with most unique habits done",
                        count = stats.dayWithMostUniqueHabits.second,
                        dateStr = stats.dayWithMostUniqueHabits.first,
                        onNavigateToDate = onNavigateToDate,
                        onClickCount = {
                            openPopup(
                                "Unique Habits on ${stats.dayWithMostUniqueHabits.first} (${stats.dayWithMostUniqueHabits.second})",
                                stats.dayWithMostUniqueHabitsList
                            )
                        }
                    )
                }

                // ── Recent Activity ───────────────────────────────────────────
                StatsSection(title = "📋 Last 7 Days", accentIndex = 13) {
                    stats.last7DaysBreakdown.forEach { (date, points) ->
                        val localDate = parseDate(date)
                        val isToday = localDate == LocalDate.now()
                        val dayLabel = if (isToday) "Today" else (localDate?.format(DISPLAY_FMT) ?: date)
                        if (localDate != null) {
                            StatDateValueRow(
                                label = dayLabel,
                                value = "$points pts",
                                date = date,
                                onNavigateToDate = onNavigateToDate
                            )
                        } else {
                            StatRow(dayLabel, "$points pts")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ── Stats list settings popup ──────────────────────────────────────────────
    if (showListSettings) {
        Dialog(onDismissRequest = { showListSettings = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Stats List Settings",
                    color = SectionTitleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = DividerColor)
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { excludeGarminFromLists = !excludeGarminFromLists },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = excludeGarminFromLists,
                        onCheckedChange = { excludeGarminFromLists = it }
                    )
                    Text(
                        text = "Exclude Garmin habits from lists",
                        color = LabelColor,
                        fontSize = 13.sp
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { excludeDisabledFromLists = !excludeDisabledFromLists },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = excludeDisabledFromLists,
                        onCheckedChange = { excludeDisabledFromLists = it }
                    )
                    Text(
                        text = "Exclude disabled habits from lists",
                        color = LabelColor,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showListSettings = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
                    ) {
                        Text("Close", color = LabelColor)
                    }
                }
            }
        }
    }

    // ── Habit list popup ──────────────────────────────────────────────────────
    if (showPopup) {
        HabitListPopup(
            title = popupTitle,
            items = popupItems,
            onDismiss = { showPopup = false }
        )
    }

    // ── Streak graph popup ────────────────────────────────────────────────────
    if (graphInfo != null) {
        StreakGraphPopup(
            title = graphInfo.title,
            data = graphInfo.data,
            lineColor = graphInfo.color,
            currentValue = graphInfo.currentValue,
            onDismiss = { graphPopupKey = null },
            onNavigateToDate = { date ->
                graphPopupKey = null
                onNavigateToDate(date)
            },
            onValueClick = graphInfo.onValueClick
        )
    }
}

// ── Habit list popup dialog ───────────────────────────────────────────────────

/**
 * A scrollable popup dialog showing a list of habits with optional value annotations.
 * [items] is a list of (habitName, valueLabel) pairs. Pass an empty valueLabel to omit it.
 */
@Composable
private fun HabitListPopup(
    title: String,
    items: List<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A1A2E), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = SectionTitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(modifier = Modifier.height(8.dp))

            if (items.isEmpty()) {
                Text(
                    text = "No habits in this list.",
                    color = DimColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(items) { (name, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                color = ValueColor,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (value.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = value,
                                    color = GoldValue,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF222233), thickness = 0.5.dp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
                ) {
                    Text("Close", color = LabelColor)
                }
            }
        }
    }
}

// ── Section composable ────────────────────────────────────────────────────────

@Composable
private fun StatsSection(
    title: String,
    accentIndex: Int = 0,
    infoText: String? = null,
    content: @Composable () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(rainbowSectionBg(accentIndex), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = rainbowTitleColor(accentIndex),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            if (infoText != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ⓘ",
                    color = DateLinkColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showInfo = true }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
    if (showInfo && infoText != null) {
        Dialog(onDismissRequest = { showInfo = false }) {
            Text(
                text = infoText,
                color = LabelColor,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
    }
}

// ── Row composables ───────────────────────────────────────────────────────────

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = ValueColor
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * A stat row where the count value is clickable (green underlined) to open a habit list popup.
 */
@Composable
private fun StatClickableCountRow(
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = count.toString(),
            color = ClickableCountColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
        )
    }
}

/**
 * A "Show all / Show top 10" toggle row for expandable top-habits lists.
 * Only rendered when the full list is longer than 10 entries.
 */
@Composable
private fun ExpandListToggle(
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    if (totalCount <= 10) return
    Text(
        text = if (expanded) "▲ Show top 10" else "▼ Show all ($totalCount)",
        color = DateLinkColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle
            )
    )
}

/**
 * A stat row with a clickable count (opens a habit list popup) and a
 * separately clickable date (navigates to that day).
 */
@Composable
private fun StatCountDateRow(
    label: String,
    count: Int,
    dateStr: String?,
    onNavigateToDate: (LocalDate) -> Unit,
    onClickCount: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = count.toString(),
                color = ClickableCountColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClickCount
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            val localDate = dateStr?.let { parseDate(it) }
            if (localDate != null) {
                Text(
                    text = localDate.format(DISPLAY_FMT),
                    color = DateLinkColor,
                    fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onNavigateToDate(localDate) }
                )
            } else {
                Text(
                    text = dateStr ?: "—",
                    color = ValueColor,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * A stat row where the value is clickable to open a graph popup.
 * Used for total streak days / total anti-streak days.
 */
@Composable
private fun StatGraphableRow(
    label: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value 📈",
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
        )
    }
}

/**
 * A stat row where the count is clickable for both a graph popup (tap the number)
 * and a habit list popup (tap the list icon). Used for habits-with-streak counts.
 */
@Composable
private fun StatGraphableCountRow(
    label: String,
    count: Int,
    valueColor: Color,
    onClickGraph: () -> Unit,
    onClickList: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = count.toString(),
                color = valueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClickList
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "📈",
                fontSize = 12.sp,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClickGraph
                )
            )
        }
    }
}

/**
 * A row with a clickable date that navigates to the main screen with that date selected.
 */
@Composable
private fun StatDateRow(
    label: String,
    dateStr: String?,
    onNavigateToDate: (LocalDate) -> Unit,
    suffix: String = ""
) {
    if (dateStr.isNullOrEmpty()) {
        StatRow(label, "—")
        return
    }
    val localDate = parseDate(dateStr)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (localDate != null) {
            Text(
                text = localDate.format(DISPLAY_FMT) + suffix,
                color = DateLinkColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onNavigateToDate(localDate) }
            )
        } else {
            Text(
                text = dateStr + suffix,
                color = ValueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * A row showing a label, a value, and a clickable date.
 */
@Composable
private fun StatDateValueRow(
    label: String,
    value: String,
    date: String?,
    onNavigateToDate: (LocalDate) -> Unit,
    dateLabel: String = ""
) {
    if (date.isNullOrEmpty()) {
        StatRow(label, "$value — —")
        return
    }
    val localDate = parseDate(date)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = GoldValue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = " — ",
                color = DimColor,
                fontSize = 12.sp
            )
            if (localDate != null) {
                Text(
                    text = localDate.format(DISPLAY_FMT) + if (dateLabel.isNotEmpty()) " $dateLabel" else "",
                    color = DateLinkColor,
                    fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onNavigateToDate(localDate) }
                )
            } else {
                Text(
                    text = date,
                    color = ValueColor,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Stats data class ──────────────────────────────────────────────────────────

private data class AppStats(
    // Overview
    val totalHabits: Int = 0,
    val allHabitsList: List<Pair<String, String>> = emptyList(),
    val totalDaysWithData: Int = 0,       // days where total points > 0
    val daysSinceFirstEntry: Long = 0,
    val firstDayWithData: String? = null,
    val lastDayWithData: String? = null,
    val totalPointsAllTime: Long = 0,
    val dailyCumulativePoints: List<Pair<String, Int>> = emptyList(),
    val dailyHabitsDone: List<Pair<String, Int>> = emptyList(),
    val dailyHabitsDoneLists: Map<String, List<Pair<String, String>>> = emptyMap(),

    // Streak aggregate stats (Overview section)
    val totalStreakDays: Int = 0,                // sum of all current streak values
    val totalAntiStreakDays: Int = 0,            // sum of all current anti-streak values
    val habitsWithStreak: Int = 0,               // count of habits with streak > 0
    val habitsWithAntiStreak: Int = 0,           // count of habits with anti-streak > 0
    val habitsWithStreakList: List<Pair<String, String>> = emptyList(),
    val habitsWithAntiStreakList: List<Pair<String, String>> = emptyList(),
    // Historical daily values for graphing
    val dailyTotalStreakDays: List<Pair<String, Int>> = emptyList(),     // date → sum of streaks
    val dailyTotalAntiStreakDays: List<Pair<String, Int>> = emptyList(), // date → sum of anti-streaks
    val dailyHabitsWithStreak: List<Pair<String, Int>> = emptyList(),    // date → count with streak
    val dailyHabitsWithAntiStreak: List<Pair<String, Int>> = emptyList(),// date → count with anti-streak

    // Highest points
    val highestPointsDay: Pair<String?, Int> = Pair(null, 0),
    val highestPointsWeek: Pair<String?, Double> = Pair(null, 0.0),
    val highestPointsMonth: Pair<String?, Double> = Pair(null, 0.0),
    val highestPoints90Days: Pair<String?, Double> = Pair(null, 0.0),
    val highestPoints365Days: Pair<String?, Double> = Pair(null, 0.0),

    // Daily averages
    val todayPoints: Int = 0,
    val avgLast7Days: Double = 0.0,
    val avgLast30Days: Double = 0.0,
    val avgLast90Days: Double = 0.0,
    val avgLast365Days: Double = 0.0,
    val avgAllTime: Double = 0.0,
    // Historical daily averages for graphing
    val dailyAvgLast7Days: List<Pair<String, Double>> = emptyList(),
    val dailyAvgLast30Days: List<Pair<String, Double>> = emptyList(),
    val dailyAvgLast90Days: List<Pair<String, Double>> = emptyList(),
    val dailyAvgLast365Days: List<Pair<String, Double>> = emptyList(),
    val dailyAvgAllTime: List<Pair<String, Double>> = emptyList(),

    // Aggregate streaks
    val currentAggregateStreak: Int = 0,
    val longestAggregateStreak: Int = 0,
    val longestAggregateStreakStartDate: String? = null,
    val longestAggregateStreakEndDate: String? = null,
    val currentZeroDayStreak: Int = 0,

    // Top habits
    val topHabitsByTotalPoints: List<Pair<String, Long>> = emptyList(),
    val topHabitsByLongestStreak: List<Triple<String, Int, String?>> = emptyList(),
    val topHabitsByCurrentStreak: List<Pair<String, Int>> = emptyList(),
    val topHabitsByAntiStreak: List<Pair<String, Int>> = emptyList(),
    val topHabitsBySingleDayHigh: List<Triple<String, Int, String>> = emptyList(),

    // Day of week / month / year averages
    val avgPointsByDayOfWeek: List<Pair<String, Double>> = emptyList(),
    val avgPointsByDayOfMonth: List<Pair<String, Double>> = emptyList(),
    val avgPointsByYear: List<Pair<String, Double>> = emptyList(),

    // Monthly
    val topMonths: List<Pair<String, Long>> = emptyList(),

    // Milestones
    val daysWithAtLeastOnePoint: Int = 0,
    val daysWithZeroPoints: Int = 0,
    val completionRate: Double = 0.0,
    val habitsDoneToday: Int = 0,
    val habitsDoneTodayList: List<Pair<String, String>> = emptyList(),
    val habitsNotDoneToday: Int = 0,
    val habitsNotDoneTodayList: List<Pair<String, String>> = emptyList(),
    val mostHabitsDoneInDayDate: String? = null,
    val mostHabitsDoneInDayCount: Int = 0,

    // Diversity
    val habitsEverDone: Int = 0,
    val habitsEverDoneList: List<Pair<String, String>> = emptyList(),
    val habitsNeverDone: Int = 0,
    val habitsNeverDoneList: List<Pair<String, String>> = emptyList(),
    val avgHabitsDonePerDay: Double = 0.0,
    val uniqueHabitsToday: Int = 0,
    val uniqueHabitsTodayList: List<Pair<String, String>> = emptyList(),
    val dayWithMostUniqueHabits: Pair<String?, Int> = Pair(null, 0),
    val dayWithMostUniqueHabitsList: List<Pair<String, String>> = emptyList(),

    // Recent
    val last7DaysBreakdown: List<Pair<String, Int>> = emptyList(),

    // Disabled habits
    val disabledHabitsCount: Int = 0
)

// ── Stats computation ─────────────────────────────────────────────────────────

private fun computeAppStats(
    db: HabitsDatabase,
    dividers: Map<String, Int>,
    disabledHabits: Set<String> = emptySet(),
    noPointsHabits: Set<String> = emptySet(),
    secondaryValueHabits: Set<String> = emptySet(),
    secondaryValueFallbackHabits: Set<String> = emptySet(),
    timerMinutesPrimaryHabits: Set<String> = emptySet(),
    invertedBinaryHabits: Set<String> = emptySet(),
    listExcludedHabits: Set<String> = emptySet()
): AppStats {
    if (db.isEmpty()) return AppStats()

    // Local helper: effective points considering value-slot fallback.
    // Minutes-primary habits read points from the dedicated minutes slot
    // (`minutes:<habit>`) with sessions as fallback. Sessions-primary habits
    // fall back to the legacy secondary slot (legacy habits) or the minutes
    // slot — the fallback value is used directly (no divider) when the
    // primary value is 0.
    fun effPts(habitName: String, raw: Int, dateStr: String): Int {
        // Inverted-binary habits: 1 point on not-done days, 0 on done days
        if (habitName in invertedBinaryHabits) return invertedBinaryPoints(raw)
        val div = dividers[habitName] ?: 1
        if (habitName in timerMinutesPrimaryHabits) {
            // Minutes primary: minutes drive points, sessions are the fallback
            val minutes = db[minutesKey(habitName)]?.get(dateStr) ?: 0
            return effectivePointsWithFallback(minutes, div, raw, true)
        }
        if (habitName !in secondaryValueFallbackHabits) return applyDivider(raw, div)
        val fallbackVal = db[com.example.tail.data.fallbackSlotKey(
            habitName, secondaryValueHabits, db
        )]?.get(dateStr) ?: 0
        return effectivePointsWithFallback(raw, div, fallbackVal, true)
    }

    val today = LocalDate.now()
    val todayStr = com.example.tail.data.dateString(today)

    // ── Collect all unique dates across all habits ─────────────────────────
    val allDates = mutableSetOf<String>()
    db.values.forEach { entries -> allDates.addAll(entries.keys) }
    // Always include today so current streak/anti-streak values are accurate
    allDates.add(todayStr)
    val sortedDates = allDates.sorted()
    if (sortedDates.isEmpty()) return AppStats()

    val firstDate = sortedDates.first()
    val firstLocalDate = parseDate(firstDate)

    // ── Build daily totals map: date → total points ───────────────────────
    val dailyTotals = mutableMapOf<String, Int>()
    val dailyHabitCounts = mutableMapOf<String, Int>()

    // Per-day list of habits done (name + points) for graph value popups
    val dailyHabitsDoneNames = mutableMapOf<String, MutableList<Pair<String, String>>>()
    for (dateStr in sortedDates) {
        var totalPoints = 0
        var habitsCount = 0
        for ((habitName, entries) in db) {
            // Skip internal value-slot storage entries and habits that don't affect points
            if (isInternalValueKey(habitName)) continue
            if (habitName in noPointsHabits) continue
            val raw = entries[dateStr] ?: 0
            val points = effPts(habitName, raw, dateStr)
            totalPoints += points
            if (points > 0) {
                habitsCount++
                dailyHabitsDoneNames.getOrPut(dateStr) { mutableListOf() }
                    .add(Pair(habitName, "$points pts"))
            }
        }
        dailyTotals[dateStr] = totalPoints
        dailyHabitCounts[dateStr] = habitsCount
    }
    val dailyHabitsDoneLists = dailyHabitsDoneNames.mapValues { (_, list) ->
        list.sortedByDescending { it.second.removeSuffix(" pts").toIntOrNull() ?: 0 }
    }

    // ── Overview ──────────────────────────────────────────────────────────
    val totalHabits = db.keys.count { !isInternalValueKey(it) }
    val allHabitsList = db.keys.filter { !isInternalValueKey(it) }.sorted().map { name ->
        val total = db[name]?.entries?.sumOf { (dateStr, raw) ->
            effPts(name, raw, dateStr).toLong()
        } ?: 0L
        Pair(name, formatLargeNumber(total) + " pts")
    }
    
    // Filter out no-points habits from habit stats calculations
    val pointHabits = db.keys.filter { it !in noPointsHabits && !isInternalValueKey(it) }

    // Days with data = days where total points > 0 (excludes zero-point days)
    val daysWithPointsSet = dailyTotals.filter { it.value > 0 }.keys
    val totalDaysWithData = daysWithPointsSet.size
    val firstDayWithData = daysWithPointsSet.minOrNull()
    val lastDayWithData = daysWithPointsSet.maxOrNull()
    val firstDayLocalDate = firstDayWithData?.let { parseDate(it) }
    val daysSinceFirst = if (firstDayLocalDate != null) ChronoUnit.DAYS.between(firstDayLocalDate, today) else 0L
    val totalPointsAllTime = dailyTotals.values.sumOf { it.toLong() }

    // ── Cumulative points over time (for graph) ─────────────────────────
    val dailyCumulativePoints = run {
        var cumulative = 0L
        dailyTotals.entries.sortedBy { it.key }.map { entry ->
            cumulative += entry.value
            Pair(entry.key, cumulative.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    // ── Habits done per day over time (for graph) ────────────────────────
    val dailyHabitsDone = dailyHabitCounts.entries.sortedBy { it.key }
        .map { Pair(it.key, it.value) }

    // ── Highest points day ────────────────────────────────────────────────
    val bestDay = dailyTotals.maxByOrNull { it.value }
    val highestPointsDay = Pair(bestDay?.key, bestDay?.value ?: 0)

    // ── Highest points week (7-day rolling average) ───────────────────────
    val sortedDailyEntries = dailyTotals.entries.sortedBy { it.key }
    var bestWeekAvg = 0.0
    var bestWeekEndDate: String? = null
    if (sortedDailyEntries.size >= 7) {
        for (i in 6 until sortedDailyEntries.size) {
            val windowSum = (i - 6..i).sumOf { sortedDailyEntries[it].value }
            val avg = windowSum / 7.0
            if (avg > bestWeekAvg) {
                bestWeekAvg = avg
                bestWeekEndDate = sortedDailyEntries[i].key
            }
        }
    } else if (sortedDailyEntries.isNotEmpty()) {
        bestWeekAvg = sortedDailyEntries.map { it.value }.average()
        bestWeekEndDate = sortedDailyEntries.last().key
    }

    // ── Highest points month (30-day rolling average) ─────────────────────
    var bestMonthAvg = 0.0
    var bestMonthEndDate: String? = null
    if (sortedDailyEntries.size >= 30) {
        for (i in 29 until sortedDailyEntries.size) {
            val windowSum = (i - 29..i).sumOf { sortedDailyEntries[it].value }
            val avg = windowSum / 30.0
            if (avg > bestMonthAvg) {
                bestMonthAvg = avg
                bestMonthEndDate = sortedDailyEntries[i].key
            }
        }
    } else if (sortedDailyEntries.isNotEmpty()) {
        bestMonthAvg = sortedDailyEntries.map { it.value }.average()
        bestMonthEndDate = sortedDailyEntries.last().key
    }

    // ── Highest points 90-day rolling average ───────────────────────────
    var best90DayAvg = 0.0
    var best90DayEndDate: String? = null
    if (sortedDailyEntries.size >= 90) {
        for (i in 89 until sortedDailyEntries.size) {
            val windowSum = (i - 89..i).sumOf { sortedDailyEntries[it].value }
            val avg = windowSum / 90.0
            if (avg > best90DayAvg) {
                best90DayAvg = avg
                best90DayEndDate = sortedDailyEntries[i].key
            }
        }
    } else if (sortedDailyEntries.isNotEmpty()) {
        best90DayAvg = sortedDailyEntries.map { it.value }.average()
        best90DayEndDate = sortedDailyEntries.last().key
    }

    // ── Highest points 365-day rolling average ──────────────────────────
    var best365DayAvg = 0.0
    var best365DayEndDate: String? = null
    if (sortedDailyEntries.size >= 365) {
        for (i in 364 until sortedDailyEntries.size) {
            val windowSum = (i - 364..i).sumOf { sortedDailyEntries[it].value }
            val avg = windowSum / 365.0
            if (avg > best365DayAvg) {
                best365DayAvg = avg
                best365DayEndDate = sortedDailyEntries[i].key
            }
        }
    } else if (sortedDailyEntries.isNotEmpty()) {
        best365DayAvg = sortedDailyEntries.map { it.value }.average()
        best365DayEndDate = sortedDailyEntries.last().key
    }

    // ── Daily averages ────────────────────────────────────────────────────
    val todayPoints = dailyTotals[todayStr] ?: 0

    fun avgOverLastNDays(n: Int): Double {
        var sum = 0
        var count = 0
        for (i in 0 until n) {
            val ds = com.example.tail.data.dateString(today.minusDays(i.toLong()))
            val pts = dailyTotals[ds]
            if (pts != null) { sum += pts; count++ }
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }

    val avgLast7 = avgOverLastNDays(7)
    val avgLast30 = avgOverLastNDays(30)
    val avgLast90 = avgOverLastNDays(90)
    val avgLast365 = avgOverLastNDays(365)
    val avgAllTime = if (totalDaysWithData > 0) totalPointsAllTime.toDouble() / totalDaysWithData else 0.0

    // ── Aggregate streaks (days with any points > 0) ──────────────────────
    val sortedDatesList = sortedDates.toList()

    // ── Historical daily averages for graphing ─────────────────────────────
    // For each date, compute the rolling average as of that date
    fun computeHistoricalAvg(n: Int): List<Pair<String, Double>> {
        val result = mutableListOf<Pair<String, Double>>()
        for ((idx, dateStr) in sortedDatesList.withIndex()) {
            var sum = 0
            var count = 0
            for (i in 0 until n) {
                if (idx - i < 0) break
                val ds = sortedDatesList[idx - i]
                val pts = dailyTotals[ds]
                if (pts != null) { sum += pts; count++ }
            }
            val avg = if (count > 0) sum.toDouble() / count else 0.0
            result.add(Pair(dateStr, avg))
        }
        return result
    }

    val dailyAvgLast7Days = computeHistoricalAvg(7)
    val dailyAvgLast30Days = computeHistoricalAvg(30)
    val dailyAvgLast90Days = computeHistoricalAvg(90)
    val dailyAvgLast365Days = computeHistoricalAvg(365)
    val dailyAvgAllTime = computeHistoricalAvg(sortedDatesList.size) // All-time average as of each date

    var currentStreak = 0
    var cursor = today
    while (true) {
        val ds = com.example.tail.data.dateString(cursor)
        if ((dailyTotals[ds] ?: 0) > 0) { currentStreak++; cursor = cursor.minusDays(1) } else break
    }

    var currentZeroStreak = 0
    cursor = today
    while (true) {
        val ds = com.example.tail.data.dateString(cursor)
        if ((dailyTotals[ds] ?: 0) == 0) { currentZeroStreak++; cursor = cursor.minusDays(1) } else break
    }

    var longestStreak = 0
    var longestStreakStart = ""
    var longestStreakEnd = ""
    var runLength = 0
    var runStart = ""
    var prevStreakDate: LocalDate? = null
    for ((idx, dateStr) in sortedDatesList.withIndex()) {
        val currDate = parseDate(dateStr)
        // A calendar gap between dates that have data breaks the run: the
        // days in between had no data at all, so they are zero-days, not
        // contiguous streak days. Without this check a lone entry years ago
        // chains onto later data and produces a bogus giant streak.
        val gapBreak = prevStreakDate != null && currDate != null &&
            ChronoUnit.DAYS.between(prevStreakDate, currDate) > 1L
        val hasPoints = (dailyTotals[dateStr] ?: 0) > 0
        if (hasPoints && !gapBreak) {
            if (runLength == 0) runStart = dateStr
            runLength++
        } else {
            if (runLength > longestStreak) {
                longestStreak = runLength
                longestStreakStart = runStart
                longestStreakEnd = if (idx > 0) sortedDatesList[idx - 1] else runStart
            }
            if (hasPoints) { runStart = dateStr; runLength = 1 } else runLength = 0
        }
        prevStreakDate = currDate
    }
    if (runLength > longestStreak) {
        longestStreak = runLength
        longestStreakStart = runStart
        longestStreakEnd = sortedDatesList.last()
    }

    // ── Per-habit stats ───────────────────────────────────────────────────
    data class HabitStat(
        val name: String,
        val totalPoints: Long,
        val longestStreak: Int,
        val longestStreakEndDate: String,
        val currentStreak: Int,
        val antiStreak: Int,
        val singleDayHigh: Int,
        val singleDayHighDate: String
    )

    val habitStats = db.filterKeys { !isInternalValueKey(it) }.map { (habitName, entries) ->
        val divider = dividers[habitName] ?: 1
        val minutesPrimary = habitName in timerMinutesPrimaryHabits
        val useFallback = minutesPrimary || habitName in secondaryValueFallbackHabits
        val altEntries = when {
            minutesPrimary -> db[minutesKey(habitName)] ?: emptyMap()
            useFallback -> db[com.example.tail.data.fallbackSlotKey(
                habitName, secondaryValueHabits, db
            )] ?: emptyMap()
            else -> emptyMap()
        }
        // Merge the alternate-slot values so dates with only that data are
        // included. For minutes-primary habits the roles swap: minutes are
        // primary, sessions are the fallback.
        val mergedEntries = if (minutesPrimary) {
            effectiveEntriesWithFallback(altEntries, entries, true)
        } else if (useFallback) {
            effectiveEntriesWithFallback(entries, altEntries, true)
        } else entries

        var total = 0L
        var maxDay = 0
        var maxDayDate = ""
        var longest = 0
        var longestEnd = ""
        var run = 0
        var lastRunDate = ""
        var prevEntryDate: LocalDate? = null

        val sortedEntries = mergedEntries.entries.sortedBy { it.key }
        for ((dateStr, _) in sortedEntries) {
            val currDate = parseDate(dateStr)
            // Calendar gaps between entry dates break the streak run (same
            // rationale as the aggregate streak fix above).
            val gapBreak = prevEntryDate != null && currDate != null &&
                ChronoUnit.DAYS.between(prevEntryDate, currDate) > 1L
            val raw = entries[dateStr] ?: 0
            val pts = effPts(habitName, raw, dateStr)
            total += pts
            if (pts > maxDay) { maxDay = pts; maxDayDate = dateStr }
            if (pts > 0 && !gapBreak) {
                run++
                lastRunDate = dateStr
            } else {
                if (run > longest) { longest = run; longestEnd = lastRunDate }
                run = if (pts > 0) 1 else 0
                if (pts > 0) lastRunDate = dateStr
            }
            prevEntryDate = currDate
        }
        if (run > longest) { longest = run; longestEnd = lastRunDate }

        // Expand entries to include all calendar days up to today so that
        // missing days count as zeros (matching desktop/calculateStreakDisplay behavior)
        val entriesWithToday = if (mergedEntries.isNotEmpty()) {
            val mutable = mergedEntries.toMutableMap()
            if (!mutable.containsKey(todayStr)) mutable[todayStr] = 0
            mutable
        } else mergedEntries
        val expanded = expandEntriesToCalendarDaysPublic(entriesWithToday)
        val reversedExpanded = expanded.entries.sortedBy { it.key }.reversed()

        var curStreak = 0
        for ((dateStr, _) in reversedExpanded) {
            val raw = entries[dateStr] ?: 0
            if (effPts(habitName, raw, dateStr) > 0) curStreak++ else break
        }
        var antiStreak = 0
        for ((dateStr, _) in reversedExpanded) {
            val raw = entries[dateStr] ?: 0
            if (effPts(habitName, raw, dateStr) == 0) antiStreak++ else break
        }

        // If the longest streak is still ongoing, report today as its end date
        val longestEndFinal = if (longest > 0 && curStreak >= longest) todayStr else longestEnd

        HabitStat(habitName, total, longest, longestEndFinal, curStreak, antiStreak, maxDay, maxDayDate)
    }

    // ── Streak aggregate stats for Overview ─────────────────────────────
    // Exclude disabled habits from streak/anti-streak aggregates
    val enabledHabitStats = habitStats.filter { it.name !in disabledHabits }
    val totalStreakDays = enabledHabitStats.sumOf { it.currentStreak }
    val totalAntiStreakDays = enabledHabitStats.sumOf { it.antiStreak }
    val habitsWithStreakCount = enabledHabitStats.count { it.currentStreak > 0 }
    val habitsWithAntiStreakCount = enabledHabitStats.count { it.antiStreak > 0 }
    val habitsWithStreakList = enabledHabitStats.filter { it.currentStreak > 0 }
        .sortedByDescending { it.currentStreak }
        .map { Pair(it.name, "${it.currentStreak} days") }
    val habitsWithAntiStreakList = enabledHabitStats.filter { it.antiStreak > 0 }
        .sortedByDescending { it.antiStreak }
        .map { Pair(it.name, "${it.antiStreak} days") }
    val disabledHabitsCount = habitStats.count { it.name in disabledHabits }

    // ── Historical daily streak/anti-streak stats for graphing ──────────
    // For each habit on each date:
    //   anti-streak = calendar days since the habit was last done (0 if done that day)
    //   streak = consecutive days done ending on that date (0 if not done that day)
    // A habit only contributes after its first entry date.
    val perDateStreakSum = IntArray(sortedDatesList.size)
    val perDateAntiStreakSum = IntArray(sortedDatesList.size)
    val perDateStreakCount = IntArray(sortedDatesList.size)
    val perDateAntiStreakCount = IntArray(sortedDatesList.size)

    // Pre-parse dates for day calculations
    val parsedDates = sortedDatesList.map { parseDate(it) }

    for ((habitName, entries) in db) {
        // Skip internal value-slot storage entries and disabled habits
        if (isInternalValueKey(habitName)) continue
        if (habitName in disabledHabits) continue
        val divider = dividers[habitName] ?: 1
        val habitFirstDate = run {
            val primFirst = entries.keys.minOrNull()
            if (habitName in timerMinutesPrimaryHabits || habitName in secondaryValueFallbackHabits) {
                val altKey = if (habitName in timerMinutesPrimaryHabits) {
                    minutesKey(habitName)
                } else {
                    com.example.tail.data.fallbackSlotKey(habitName, secondaryValueHabits, db)
                }
                val altFirst = db[altKey]?.keys?.minOrNull()
                listOfNotNull(primFirst, altFirst).minOrNull()
            } else primFirst
        }
        // Track the last date this habit had a non-zero value
        var lastDoneDate: LocalDate? = null
        var streak = 0
        var habitStarted = false
        for ((idx, dateStr) in sortedDatesList.withIndex()) {
            // Skip dates before this habit's first entry
            if (!habitStarted) {
                if (habitFirstDate != null && dateStr >= habitFirstDate) {
                    habitStarted = true
                } else {
                    continue
                }
            }

            val currDate = parsedDates[idx] ?: continue
            val raw = entries[dateStr] ?: 0
            val pts = effPts(habitName, raw, dateStr)

            if (pts > 0) {
                // Calculate streak: consecutive days done ending today
                if (lastDoneDate != null && ChronoUnit.DAYS.between(lastDoneDate, currDate) == 1L) {
                    streak++
                } else {
                    streak = 1
                }
                lastDoneDate = currDate
                perDateStreakSum[idx] += streak
                perDateStreakCount[idx]++
                // Anti-streak is 0 when done today — don't add anything
            } else {
                streak = 0
                // Anti-streak = days since last done
                val antiStrk = if (lastDoneDate != null) {
                    ChronoUnit.DAYS.between(lastDoneDate, currDate).toInt()
                } else {
                    // Never done — count from habit's first entry date
                    val firstDate = parseDate(habitFirstDate ?: dateStr)
                    if (firstDate != null) ChronoUnit.DAYS.between(firstDate, currDate).toInt()
                    else 0
                }
                if (antiStrk > 0) {
                    perDateAntiStreakSum[idx] += antiStrk
                    perDateAntiStreakCount[idx]++
                }
            }
        }
    }

    val dailyStreakTotals = sortedDatesList.mapIndexed { idx, d -> Pair(d, perDateStreakSum[idx]) }
    val dailyAntiStreakTotals = sortedDatesList.mapIndexed { idx, d -> Pair(d, perDateAntiStreakSum[idx]) }
    val dailyStreakCounts = sortedDatesList.mapIndexed { idx, d -> Pair(d, perDateStreakCount[idx]) }
    val dailyAntiStreakCounts = sortedDatesList.mapIndexed { idx, d -> Pair(d, perDateAntiStreakCount[idx]) }

    // Habits excluded from the ranked lists (Garmin-linked and/or disabled,
    // per the stats settings popup). Only the top-10 lists and the diversity
    // ever/never-done lists are filtered; point totals and streak aggregates
    // stay untouched.
    val listHabitStats = habitStats.filter { it.name !in listExcludedHabits }
    // Full ranked lists — the UI slices the top 10 and can expand to all.
    val topByTotal = listHabitStats.sortedByDescending { it.totalPoints }
        .map { Pair(it.name, it.totalPoints) }
    val topByLongestStreak = listHabitStats.sortedByDescending { it.longestStreak }
        .map { Triple(it.name, it.longestStreak, it.longestStreakEndDate) }
    val topByCurrentStreak = listHabitStats.filter { it.currentStreak > 0 }
        .sortedByDescending { it.currentStreak }
        .map { Pair(it.name, it.currentStreak) }
    val topByAntiStreak = listHabitStats.filter { it.antiStreak > 0 }
        .sortedByDescending { it.antiStreak }
        .map { Pair(it.name, it.antiStreak) }
    val topBySingleDay = listHabitStats.filter { it.singleDayHigh > 0 }
        .sortedByDescending { it.singleDayHigh }
        .map { Triple(it.name, it.singleDayHigh, it.singleDayHighDate) }

    // ── Day of week / day of month / year analysis ────────────────────────
    // Full weeks (or months) with zero points are excluded from the weekday
    // (or day-of-month) averages so dormant periods don't drag them down.
    // Years with no data or all-zero days are excluded from the yearly list.
    fun isoWeekKey(ld: LocalDate): String {
        val wf = java.time.temporal.WeekFields.ISO
        return "%04d-W%02d".format(ld.get(wf.weekBasedYear()), ld.get(wf.weekOfWeekBasedYear()))
    }

    val weekTotals = mutableMapOf<String, Long>()
    val monthTotalsForAvg = mutableMapOf<String, Long>()
    for ((dateStr, pts) in dailyTotals) {
        val ld = parseDate(dateStr) ?: continue
        val wk = isoWeekKey(ld)
        weekTotals[wk] = (weekTotals[wk] ?: 0L) + pts
        val mk = dateStr.substring(0, 7)
        monthTotalsForAvg[mk] = (monthTotalsForAvg[mk] ?: 0L) + pts
    }

    val dayOfWeekSums = mutableMapOf<DayOfWeek, Long>()
    val dayOfWeekCounts = mutableMapOf<DayOfWeek, Int>()
    val dayOfMonthSums = mutableMapOf<Int, Long>()
    val dayOfMonthCounts = mutableMapOf<Int, Int>()
    for ((dateStr, pts) in dailyTotals) {
        val ld = parseDate(dateStr) ?: continue
        if ((weekTotals[isoWeekKey(ld)] ?: 0L) > 0L) {
            val dow = ld.dayOfWeek
            dayOfWeekSums[dow] = (dayOfWeekSums[dow] ?: 0L) + pts
            dayOfWeekCounts[dow] = (dayOfWeekCounts[dow] ?: 0) + 1
        }
        if ((monthTotalsForAvg[dateStr.substring(0, 7)] ?: 0L) > 0L) {
            val dom = ld.dayOfMonth
            dayOfMonthSums[dom] = (dayOfMonthSums[dom] ?: 0L) + pts
            dayOfMonthCounts[dom] = (dayOfMonthCounts[dom] ?: 0) + 1
        }
    }
    val dayOfWeekOrder = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    )
    val avgByDow = dayOfWeekOrder.map { dow ->
        val sum = dayOfWeekSums[dow] ?: 0L
        val count = dayOfWeekCounts[dow] ?: 1
        Pair(dow.name.lowercase().replaceFirstChar { it.uppercase() }, sum.toDouble() / count)
    }
    val avgByDom = (1..31).map { dom ->
        val sum = dayOfMonthSums[dom] ?: 0L
        val count = dayOfMonthCounts[dom] ?: 1
        Pair("Day $dom", sum.toDouble() / count)
    }

    // Yearly averages: total points per year divided by the days of that year
    // present in the dataset. Years with zero total points are omitted.
    val yearSums = mutableMapOf<Int, Long>()
    val yearDayCounts = mutableMapOf<Int, Int>()
    for ((dateStr, pts) in dailyTotals) {
        val year = dateStr.substring(0, 4).toIntOrNull() ?: continue
        yearSums[year] = (yearSums[year] ?: 0L) + pts
        yearDayCounts[year] = (yearDayCounts[year] ?: 0) + 1
    }
    val avgByYear = yearSums.entries
        .filter { it.value > 0L }
        .sortedBy { it.key }
        .map { (year, sum) -> Pair(year.toString(), sum.toDouble() / (yearDayCounts[year] ?: 1)) }

    // ── Monthly totals ────────────────────────────────────────────────────
    val monthlyTotals = mutableMapOf<String, Long>()
    for ((dateStr, pts) in dailyTotals) {
        val monthKey = dateStr.substring(0, 7)
        monthlyTotals[monthKey] = (monthlyTotals[monthKey] ?: 0L) + pts
    }
    val topMonths = monthlyTotals.entries.sortedByDescending { it.value }.take(10)
        .map { Pair(it.key, it.value) }

    // ── Milestones ────────────────────────────────────────────────────────
    val daysWithPoints = dailyTotals.count { it.value > 0 }
    val daysWithZero = dailyTotals.count { it.value == 0 }
    val completionRate = if (dailyTotals.isNotEmpty()) daysWithPoints.toDouble() / dailyTotals.size * 100 else 0.0

    var habitsDoneToday = 0
    var habitsNotDoneToday = 0
    val habitsDoneTodayList = mutableListOf<Pair<String, String>>()
    val habitsNotDoneTodayList = mutableListOf<Pair<String, String>>()
    for ((habitName, entries) in db) {
        if (isInternalValueKey(habitName)) continue
        val raw = entries[todayStr] ?: 0
        val pts = effPts(habitName, raw, todayStr)
        if (pts > 0) {
            habitsDoneToday++
            habitsDoneTodayList.add(Pair(habitName, "$pts pts"))
        } else {
            habitsNotDoneToday++
            habitsNotDoneTodayList.add(Pair(habitName, ""))
        }
    }
    habitsDoneTodayList.sortByDescending { it.second }
    habitsNotDoneTodayList.sortBy { it.first }

    val mostHabitsDay = dailyHabitCounts.maxByOrNull { it.value }

    // ── Diversity ─────────────────────────────────────────────────────────
    val habitsEverDoneList = listHabitStats.filter { it.totalPoints > 0 }
        .sortedByDescending { it.totalPoints }
        .map { Pair(it.name, formatLargeNumber(it.totalPoints) + " pts") }
    val habitsNeverDoneList = listHabitStats.filter { it.totalPoints == 0L }
        .sortedBy { it.name }
        .map { Pair(it.name, "") }
    val avgHabitsDonePerDay = if (dailyTotals.isNotEmpty())
        dailyHabitCounts.values.sum().toDouble() / dailyTotals.size else 0.0
    val dayMostUnique = dailyHabitCounts.maxByOrNull { it.value }
    // Unique-habits-done lists (respect the list exclusion toggles so the
    // popup contents match the displayed counts)
    val uniqueTodayList = mutableListOf<Pair<String, String>>()
    val mostUniqueDayList = mutableListOf<Pair<String, String>>()
    val mostUniqueDayDateStr = dayMostUnique?.key
    for ((habitName, entries) in db) {
        if (isInternalValueKey(habitName)) continue
        if (habitName in noPointsHabits) continue
        if (habitName in listExcludedHabits) continue
        val ptsToday = effPts(habitName, entries[todayStr] ?: 0, todayStr)
        if (ptsToday > 0) uniqueTodayList.add(Pair(habitName, "$ptsToday pts"))
        if (mostUniqueDayDateStr != null) {
            val ptsThatDay = effPts(habitName, entries[mostUniqueDayDateStr] ?: 0, mostUniqueDayDateStr)
            if (ptsThatDay > 0) mostUniqueDayList.add(Pair(habitName, "$ptsThatDay pts"))
        }
    }
    uniqueTodayList.sortByDescending { it.second }
    mostUniqueDayList.sortByDescending { it.second }
    val uniqueHabitsToday = uniqueTodayList.size

    // ── Last 7 days breakdown ─────────────────────────────────────────────
    val last7Days = (0 until 7).map { i ->
        val d = today.minusDays(i.toLong())
        val ds = com.example.tail.data.dateString(d)
        Pair(ds, dailyTotals[ds] ?: 0)
    }

    return AppStats(
        totalHabits = totalHabits,
        allHabitsList = allHabitsList,
        totalDaysWithData = totalDaysWithData,
        daysSinceFirstEntry = daysSinceFirst,
        firstDayWithData = firstDayWithData,
        lastDayWithData = lastDayWithData,
        totalPointsAllTime = totalPointsAllTime,
        dailyCumulativePoints = dailyCumulativePoints,
        dailyHabitsDone = dailyHabitsDone,
        dailyHabitsDoneLists = dailyHabitsDoneLists,
        totalStreakDays = totalStreakDays,
        totalAntiStreakDays = totalAntiStreakDays,
        habitsWithStreak = habitsWithStreakCount,
        habitsWithAntiStreak = habitsWithAntiStreakCount,
        habitsWithStreakList = habitsWithStreakList,
        habitsWithAntiStreakList = habitsWithAntiStreakList,
        dailyTotalStreakDays = dailyStreakTotals,
        dailyTotalAntiStreakDays = dailyAntiStreakTotals,
        dailyHabitsWithStreak = dailyStreakCounts,
        dailyHabitsWithAntiStreak = dailyAntiStreakCounts,
        highestPointsDay = highestPointsDay,
        highestPointsWeek = Pair(bestWeekEndDate, bestWeekAvg),
        highestPointsMonth = Pair(bestMonthEndDate, bestMonthAvg),
        highestPoints90Days = Pair(best90DayEndDate, best90DayAvg),
        highestPoints365Days = Pair(best365DayEndDate, best365DayAvg),
        todayPoints = todayPoints,
        avgLast7Days = avgLast7,
        avgLast30Days = avgLast30,
        avgLast90Days = avgLast90,
        avgLast365Days = avgLast365,
        avgAllTime = avgAllTime,
        dailyAvgLast7Days = dailyAvgLast7Days,
        dailyAvgLast30Days = dailyAvgLast30Days,
        dailyAvgLast90Days = dailyAvgLast90Days,
        dailyAvgLast365Days = dailyAvgLast365Days,
        dailyAvgAllTime = dailyAvgAllTime,
        currentAggregateStreak = currentStreak,
        longestAggregateStreak = longestStreak,
        longestAggregateStreakStartDate = longestStreakStart.ifEmpty { null },
        longestAggregateStreakEndDate = longestStreakEnd.ifEmpty { null },
        currentZeroDayStreak = currentZeroStreak,
        topHabitsByTotalPoints = topByTotal,
        topHabitsByLongestStreak = topByLongestStreak,
        topHabitsByCurrentStreak = topByCurrentStreak,
        topHabitsByAntiStreak = topByAntiStreak,
        topHabitsBySingleDayHigh = topBySingleDay,
        avgPointsByDayOfWeek = avgByDow,
        avgPointsByDayOfMonth = avgByDom,
        avgPointsByYear = avgByYear,
        topMonths = topMonths,
        daysWithAtLeastOnePoint = daysWithPoints,
        daysWithZeroPoints = daysWithZero,
        completionRate = completionRate,
        habitsDoneToday = habitsDoneToday,
        habitsDoneTodayList = habitsDoneTodayList,
        habitsNotDoneToday = habitsNotDoneToday,
        habitsNotDoneTodayList = habitsNotDoneTodayList,
        mostHabitsDoneInDayDate = mostHabitsDay?.key,
        mostHabitsDoneInDayCount = mostHabitsDay?.value ?: 0,
        habitsEverDone = habitsEverDoneList.size,
        habitsEverDoneList = habitsEverDoneList,
        habitsNeverDone = habitsNeverDoneList.size,
        habitsNeverDoneList = habitsNeverDoneList,
        avgHabitsDonePerDay = avgHabitsDonePerDay,
        uniqueHabitsToday = uniqueHabitsToday,
        uniqueHabitsTodayList = uniqueTodayList,
        dayWithMostUniqueHabits = Pair(dayMostUnique?.key, dayMostUnique?.value ?: 0),
        dayWithMostUniqueHabitsList = mostUniqueDayList,
        last7DaysBreakdown = last7Days,
        disabledHabitsCount = disabledHabitsCount
    )
}

private fun formatStreakDays(n: Int): String =
    if (n >= 365) "${n}d (${n / 365}y ${n % 365}d)" else "${n}d"

private fun formatLargeNumber(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

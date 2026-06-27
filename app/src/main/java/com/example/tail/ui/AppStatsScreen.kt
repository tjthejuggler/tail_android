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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.applyDivider
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.parseDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── Color palette ─────────────────────────────────────────────────────────────
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
    val noPointsHabits = settings.noPointsHabits

    // Compute all stats from the cached database
    val db = viewModel.getCachedDatabase()
    val stats = remember(db, dividers, disabledHabits, noPointsHabits) { computeAppStats(db, dividers, disabledHabits, noPointsHabits) }

    // State for the habit-list popup
    var popupTitle by remember { mutableStateOf("") }
    var popupItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showPopup by remember { mutableStateOf(false) }

    // State for the streak graph popup — use rememberSaveable so it survives
    // the configuration change triggered by forcing landscape orientation.
    // We store a graph key (string) and derive data from stats on recomposition.
    var graphPopupKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Derive graph data from the key + stats
    data class GraphInfo(
        val title: String,
        val data: List<Pair<String, Int>>,
        val color: Color,
        val currentValue: Int?
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
        "avg_last_7_days" -> GraphInfo(
            "7-Day Rolling Average Over Time",
            stats.dailyAvgLast7Days.map { Pair(it.first, it.second.toInt()) },
            ValueColor,
            stats.avgLast7Days.toInt()
        )
        "avg_last_30_days" -> GraphInfo(
            "30-Day Rolling Average Over Time",
            stats.dailyAvgLast30Days.map { Pair(it.first, it.second.toInt()) },
            ValueColor,
            stats.avgLast30Days.toInt()
        )
        "avg_last_90_days" -> GraphInfo(
            "90-Day Rolling Average Over Time",
            stats.dailyAvgLast90Days.map { Pair(it.first, it.second.toInt()) },
            ValueColor,
            stats.avgLast90Days.toInt()
        )
        "avg_last_365_days" -> GraphInfo(
            "365-Day Rolling Average Over Time",
            stats.dailyAvgLast365Days.map { Pair(it.first, it.second.toInt()) },
            ValueColor,
            stats.avgLast365Days.toInt()
        )
        "avg_all_time" -> GraphInfo(
            "All-Time Rolling Average Over Time",
            stats.dailyAvgAllTime.map { Pair(it.first, it.second.toInt()) },
            ValueColor,
            stats.avgAllTime.toInt()
        )
        else -> null
    }

    fun openPopup(title: String, items: List<Pair<String, String>>) {
        popupTitle = title
        popupItems = items
        showPopup = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                StatsSection(title = "📊 Overview") {
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
                StatsSection(title = "🏆 Highest Points") {
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
                StatsSection(title = "📈 Daily Averages") {
                    StatRow("Today's points", stats.todayPoints.toString())
                    StatGraphableRow(
                        label = "Average (last 7 days)",
                        value = "%.2f".format(stats.avgLast7Days),
                        valueColor = ValueColor,
                        onClick = { graphPopupKey = "avg_last_7_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 30 days)",
                        value = "%.2f".format(stats.avgLast30Days),
                        valueColor = ValueColor,
                        onClick = { graphPopupKey = "avg_last_30_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 90 days)",
                        value = "%.2f".format(stats.avgLast90Days),
                        valueColor = ValueColor,
                        onClick = { graphPopupKey = "avg_last_90_days" }
                    )
                    StatGraphableRow(
                        label = "Average (last 365 days)",
                        value = "%.2f".format(stats.avgLast365Days),
                        valueColor = ValueColor,
                        onClick = { graphPopupKey = "avg_last_365_days" }
                    )
                    StatGraphableRow(
                        label = "Average (all time)",
                        value = "%.2f".format(stats.avgAllTime),
                        valueColor = ValueColor,
                        onClick = { graphPopupKey = "avg_all_time" }
                    )
                }

                // ── Streaks (aggregate) ───────────────────────────────────────
                StatsSection(title = "🔥 Aggregate Streaks") {
                    StatRow(
                        "Current streak (days with any points)",
                        "${stats.currentAggregateStreak} days",
                        valueColor = if (stats.currentAggregateStreak > 0) GreenValue else DimColor
                    )
                    StatRow(
                        "Longest streak (days with any points)",
                        "${stats.longestAggregateStreak} days",
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
                        "${stats.currentZeroDayStreak} days",
                        valueColor = if (stats.currentZeroDayStreak > 0) RedValue else DimColor
                    )
                }

                // ── Top Habits by Total Points ────────────────────────────────
                StatsSection(title = "⭐ Top 10 Habits by Total Points") {
                    stats.topHabitsByTotalPoints.forEachIndexed { index, (name, points) ->
                        StatRow(
                            "${index + 1}. $name",
                            formatLargeNumber(points),
                            valueColor = when (index) {
                                0 -> GoldValue
                                1 -> Color(0xFFC0C0C0)
                                2 -> Color(0xFFCD7F32)
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Top Habits by Longest Streak ──────────────────────────────
                StatsSection(title = "🔗 Top 10 Habits by Longest Streak") {
                    stats.topHabitsByLongestStreak.forEachIndexed { index, (name, streak) ->
                        StatRow(
                            "${index + 1}. $name",
                            "$streak days",
                            valueColor = when (index) {
                                0 -> GoldValue
                                1 -> Color(0xFFC0C0C0)
                                2 -> Color(0xFFCD7F32)
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Top Habits by Current Streak ──────────────────────────────
                StatsSection(title = "🏃 Top 10 Habits by Current Streak") {
                    stats.topHabitsByCurrentStreak.forEachIndexed { index, (name, streak) ->
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
                }

                // ── Worst Anti-Streaks ────────────────────────────────────────
                StatsSection(title = "💤 Top 10 Longest Current Anti-Streaks") {
                    stats.topHabitsByAntiStreak.forEachIndexed { index, (name, antiStreak) ->
                        StatRow(
                            "${index + 1}. $name",
                            "$antiStreak days",
                            valueColor = RedValue
                        )
                    }
                }

                // ── Habits with Highest Single-Day Count ──────────────────────
                StatsSection(title = "💥 Highest Single-Day Count per Habit") {
                    stats.topHabitsBySingleDayHigh.forEachIndexed { index, triple ->
                        StatDateValueRow(
                            label = "${index + 1}. ${triple.first}",
                            value = triple.second.toString(),
                            date = triple.third,
                            onNavigateToDate = onNavigateToDate
                        )
                    }
                }

                // ── Day of Week Analysis ──────────────────────────────────────
                StatsSection(title = "📅 Average Points by Day of Week") {
                    stats.avgPointsByDayOfWeek.forEach { (dayName, avg) ->
                        val isHighest = avg == stats.avgPointsByDayOfWeek.maxByOrNull { it.second }?.second
                        StatRow(
                            dayName,
                            "%.2f".format(avg),
                            valueColor = if (isHighest) GoldValue else ValueColor
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    StatRow(
                        "Best day of the week",
                        stats.bestDayOfWeek,
                        valueColor = GoldValue
                    )
                    StatRow(
                        "Worst day of the week",
                        stats.worstDayOfWeek,
                        valueColor = RedValue
                    )
                }

                // ── Monthly Trends ────────────────────────────────────────────
                StatsSection(title = "📆 Best Months (Total Points)") {
                    stats.topMonths.forEachIndexed { index, (monthLabel, points) ->
                        StatRow(
                            "${index + 1}. $monthLabel",
                            formatLargeNumber(points),
                            valueColor = when (index) {
                                0 -> GoldValue
                                1 -> Color(0xFFC0C0C0)
                                2 -> Color(0xFFCD7F32)
                                else -> ValueColor
                            }
                        )
                    }
                }

                // ── Milestones ────────────────────────────────────────────────
                StatsSection(title = "🎯 Milestones") {
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
                StatsSection(title = "🌈 Habit Diversity") {
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
                    StatDateRow(
                        "Day with most unique habits done",
                        stats.dayWithMostUniqueHabits.first,
                        onNavigateToDate,
                        suffix = " (${stats.dayWithMostUniqueHabits.second} habits)"
                    )
                }

                // ── Recent Activity ───────────────────────────────────────────
                StatsSection(title = "📋 Last 7 Days") {
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
            onDismiss = { graphPopupKey = null }
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
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(SectionBg, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = SectionTitleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
        content()
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
    val topHabitsByLongestStreak: List<Pair<String, Int>> = emptyList(),
    val topHabitsByCurrentStreak: List<Pair<String, Int>> = emptyList(),
    val topHabitsByAntiStreak: List<Pair<String, Int>> = emptyList(),
    val topHabitsBySingleDayHigh: List<Triple<String, Int, String>> = emptyList(),

    // Day of week
    val avgPointsByDayOfWeek: List<Pair<String, Double>> = emptyList(),
    val bestDayOfWeek: String = "",
    val worstDayOfWeek: String = "",

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
    val dayWithMostUniqueHabits: Pair<String?, Int> = Pair(null, 0),

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
    noPointsHabits: Set<String> = emptySet()
): AppStats {
    if (db.isEmpty()) return AppStats()

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

    for (dateStr in sortedDates) {
        var totalPoints = 0
        var habitsCount = 0
        for ((habitName, entries) in db) {
            // Skip habits that don't affect points
            if (habitName in noPointsHabits) continue
            val raw = entries[dateStr] ?: 0
            val points = applyDivider(raw, dividers[habitName] ?: 1)
            totalPoints += points
            if (points > 0) habitsCount++
        }
        dailyTotals[dateStr] = totalPoints
        dailyHabitCounts[dateStr] = habitsCount
    }

    // ── Overview ──────────────────────────────────────────────────────────
    val totalHabits = db.size
    val allHabitsList = db.keys.sorted().map { name ->
        val total = db[name]?.entries?.sumOf { (_, raw) ->
            applyDivider(raw, dividers[name] ?: 1).toLong()
        } ?: 0L
        Pair(name, formatLargeNumber(total) + " pts")
    }
    
    // Filter out no-points habits from habit stats calculations
    val pointHabits = db.keys.filter { it !in noPointsHabits }

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
    for ((idx, dateStr) in sortedDatesList.withIndex()) {
        if ((dailyTotals[dateStr] ?: 0) > 0) {
            if (runLength == 0) runStart = dateStr
            runLength++
        } else {
            if (runLength > longestStreak) {
                longestStreak = runLength
                longestStreakStart = runStart
                longestStreakEnd = if (idx > 0) sortedDatesList[idx - 1] else runStart
            }
            runLength = 0
        }
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
        val currentStreak: Int,
        val antiStreak: Int,
        val singleDayHigh: Int,
        val singleDayHighDate: String
    )

    val habitStats = db.map { (habitName, entries) ->
        val divider = dividers[habitName] ?: 1
        var total = 0L
        var maxDay = 0
        var maxDayDate = ""
        var longest = 0
        var run = 0

        val sortedEntries = entries.entries.sortedBy { it.key }
        for ((dateStr, rawVal) in sortedEntries) {
            val pts = applyDivider(rawVal, divider)
            total += pts
            if (pts > maxDay) { maxDay = pts; maxDayDate = dateStr }
            if (pts > 0) run++ else { longest = maxOf(longest, run); run = 0 }
        }
        longest = maxOf(longest, run)

        // Expand entries to include all calendar days up to today so that
        // missing days count as zeros (matching desktop/calculateStreakDisplay behavior)
        val entriesWithToday = if (entries.isNotEmpty()) {
            val mutable = entries.toMutableMap()
            if (!mutable.containsKey(todayStr)) mutable[todayStr] = 0
            mutable
        } else entries
        val expanded = expandEntriesToCalendarDaysPublic(entriesWithToday)
        val reversedExpanded = expanded.entries.sortedBy { it.key }.reversed()

        var curStreak = 0
        for ((_, rawVal) in reversedExpanded) {
            if (applyDivider(rawVal, divider) > 0) curStreak++ else break
        }
        var antiStreak = 0
        for ((_, rawVal) in reversedExpanded) {
            if (applyDivider(rawVal, divider) == 0) antiStreak++ else break
        }

        HabitStat(habitName, total, longest, curStreak, antiStreak, maxDay, maxDayDate)
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
        // Skip disabled habits in historical streak/anti-streak graphs
        if (habitName in disabledHabits) continue
        val divider = dividers[habitName] ?: 1
        val habitFirstDate = entries.keys.minOrNull()
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
            val pts = applyDivider(raw, divider)

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

    val topByTotal = habitStats.sortedByDescending { it.totalPoints }.take(10)
        .map { Pair(it.name, it.totalPoints) }
    val topByLongestStreak = habitStats.sortedByDescending { it.longestStreak }.take(10)
        .map { Pair(it.name, it.longestStreak) }
    val topByCurrentStreak = habitStats.filter { it.currentStreak > 0 }
        .sortedByDescending { it.currentStreak }.take(10)
        .map { Pair(it.name, it.currentStreak) }
    val topByAntiStreak = habitStats.filter { it.antiStreak > 0 }
        .sortedByDescending { it.antiStreak }.take(10)
        .map { Pair(it.name, it.antiStreak) }
    val topBySingleDay = habitStats.filter { it.singleDayHigh > 0 }
        .sortedByDescending { it.singleDayHigh }.take(10)
        .map { Triple(it.name, it.singleDayHigh, it.singleDayHighDate) }

    // ── Day of week analysis ──────────────────────────────────────────────
    val dayOfWeekSums = mutableMapOf<DayOfWeek, Long>()
    val dayOfWeekCounts = mutableMapOf<DayOfWeek, Int>()
    for ((dateStr, pts) in dailyTotals) {
        val ld = parseDate(dateStr) ?: continue
        val dow = ld.dayOfWeek
        dayOfWeekSums[dow] = (dayOfWeekSums[dow] ?: 0L) + pts
        dayOfWeekCounts[dow] = (dayOfWeekCounts[dow] ?: 0) + 1
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
    val bestDow = avgByDow.maxByOrNull { it.second }?.first ?: ""
    val worstDow = avgByDow.minByOrNull { it.second }?.first ?: ""

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
        val raw = entries[todayStr] ?: 0
        val pts = applyDivider(raw, dividers[habitName] ?: 1)
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
    val habitsEverDoneList = habitStats.filter { it.totalPoints > 0 }
        .sortedByDescending { it.totalPoints }
        .map { Pair(it.name, formatLargeNumber(it.totalPoints) + " pts") }
    val habitsNeverDoneList = habitStats.filter { it.totalPoints == 0L }
        .sortedBy { it.name }
        .map { Pair(it.name, "") }
    val avgHabitsDonePerDay = if (dailyTotals.isNotEmpty())
        dailyHabitCounts.values.sum().toDouble() / dailyTotals.size else 0.0
    val dayMostUnique = dailyHabitCounts.maxByOrNull { it.value }

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
        bestDayOfWeek = bestDow,
        worstDayOfWeek = worstDow,
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
        dayWithMostUniqueHabits = Pair(dayMostUnique?.key, dayMostUnique?.value ?: 0),
        last7DaysBreakdown = last7Days,
        disabledHabitsCount = disabledHabitsCount
    )
}

private fun formatLargeNumber(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

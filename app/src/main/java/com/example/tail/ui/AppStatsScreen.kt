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

    // Compute all stats from the cached database
    val db = viewModel.getCachedDatabase()
    val stats = remember(db, dividers) { computeAppStats(db, dividers) }

    // State for the habit-list popup
    var popupTitle by remember { mutableStateOf("") }
    var popupItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showPopup by remember { mutableStateOf(false) }

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
                    StatRow("Total days with data (>0 pts)", stats.totalDaysWithData.toString())
                    StatRow("Days since first entry", stats.daysSinceFirstEntry.toString())
                    StatDateRow("First day with data", stats.firstDayWithData, onNavigateToDate)
                    StatDateRow("Most recent day with data", stats.lastDayWithData, onNavigateToDate)
                    StatRow("Total habit points (all time)", formatLargeNumber(stats.totalPointsAllTime))
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
                }

                // ── Daily Averages ────────────────────────────────────────────
                StatsSection(title = "📈 Daily Averages") {
                    StatRow("Today's points", stats.todayPoints.toString())
                    StatRow("Average (last 7 days)", "%.2f".format(stats.avgLast7Days))
                    StatRow("Average (last 30 days)", "%.2f".format(stats.avgLast30Days))
                    StatRow("Average (last 90 days)", "%.2f".format(stats.avgLast90Days))
                    StatRow("Average (last 365 days)", "%.2f".format(stats.avgLast365Days))
                    StatRow("Average (all time)", "%.2f".format(stats.avgAllTime))
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

    // Highest points
    val highestPointsDay: Pair<String?, Int> = Pair(null, 0),
    val highestPointsWeek: Pair<String?, Double> = Pair(null, 0.0),
    val highestPointsMonth: Pair<String?, Double> = Pair(null, 0.0),

    // Daily averages
    val todayPoints: Int = 0,
    val avgLast7Days: Double = 0.0,
    val avgLast30Days: Double = 0.0,
    val avgLast90Days: Double = 0.0,
    val avgLast365Days: Double = 0.0,
    val avgAllTime: Double = 0.0,

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
    val last7DaysBreakdown: List<Pair<String, Int>> = emptyList()
)

// ── Stats computation ─────────────────────────────────────────────────────────

private fun computeAppStats(
    db: HabitsDatabase,
    dividers: Map<String, Int>
): AppStats {
    if (db.isEmpty()) return AppStats()

    val today = LocalDate.now()
    val todayStr = com.example.tail.data.dateString(today)

    // ── Collect all unique dates across all habits ─────────────────────────
    val allDates = mutableSetOf<String>()
    db.values.forEach { entries -> allDates.addAll(entries.keys) }
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

    // Days with data = days where total points > 0 (excludes zero-point days)
    val daysWithPointsSet = dailyTotals.filter { it.value > 0 }.keys
    val totalDaysWithData = daysWithPointsSet.size
    val firstDayWithData = daysWithPointsSet.minOrNull()
    val lastDayWithData = daysWithPointsSet.maxOrNull()
    val firstDayLocalDate = firstDayWithData?.let { parseDate(it) }
    val daysSinceFirst = if (firstDayLocalDate != null) ChronoUnit.DAYS.between(firstDayLocalDate, today) else 0L
    val totalPointsAllTime = dailyTotals.values.sumOf { it.toLong() }

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

        val reversed = sortedEntries.reversed()
        var curStreak = 0
        for ((_, rawVal) in reversed) {
            if (applyDivider(rawVal, divider) > 0) curStreak++ else break
        }
        var antiStreak = 0
        for ((_, rawVal) in reversed) {
            if (applyDivider(rawVal, divider) == 0) antiStreak++ else break
        }

        HabitStat(habitName, total, longest, curStreak, antiStreak, maxDay, maxDayDate)
    }

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
        highestPointsDay = highestPointsDay,
        highestPointsWeek = Pair(bestWeekEndDate, bestWeekAvg),
        highestPointsMonth = Pair(bestMonthEndDate, bestMonthAvg),
        todayPoints = todayPoints,
        avgLast7Days = avgLast7,
        avgLast30Days = avgLast30,
        avgLast90Days = avgLast90,
        avgLast365Days = avgLast365,
        avgAllTime = avgAllTime,
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
        last7DaysBreakdown = last7Days
    )
}

private fun formatLargeNumber(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

package com.example.tail.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.dateString
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/**
 * One timed occurrence of a habit on the schedule — a group of increments
 * that were recorded at the same moment (the repository stores one
 * "HH:mm:ss" string per unit; duplicates at one moment form one event).
 */
data class ScheduleEvent(
    val habitName: String,
    /** "HH:mm:ss" time-of-day string. */
    val time: String,
    /** Number of increment units recorded at this moment. */
    val amount: Int,
    val isMeal: Boolean,
    val canEditText: Boolean
) {
    val minuteOfDay: Int by lazy {
        runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.NOON).let { it.hour * 60 + it.minute }
    }
}

/** Vertical size of one hour on the timeline. */
private val HOUR_HEIGHT = 64.dp
/** Width of the hour-label gutter on the left. */
private val GUTTER_WIDTH = 46.dp
/** Visual height of one event chip (≈28 minutes on the timeline). */
private val EVENT_HEIGHT = 30.dp

/**
 * Harmonious accent palette for event chips. A habit's colour is derived
 * stably from its name so it keeps the same colour across recompositions.
 */
private val SCHEDULE_PALETTE = listOf(
    Color(0xFF66CCFF), // sky blue
    Color(0xFFFF69B4), // pink
    Color(0xFF66DD66), // green
    Color(0xFFFFAA00), // orange
    Color(0xFFB388FF), // purple
    Color(0xFF44DDDD), // teal
    Color(0xFFFFD700), // gold
    Color(0xFFFF8A80), // salmon
    Color(0xFFA5D6A7), // mint
    Color(0xFFF48FB1)  // rose
)

/** Stable accent colour for a habit name. */
fun scheduleAccentFor(habitName: String): Color =
    SCHEDULE_PALETTE[Math.floorMod(habitName.hashCode(), SCHEDULE_PALETTE.size)]

/**
 * The Daily Schedule — a retrospective, hour-by-hour timeline of what was
 * actually done on the selected day.
 *
 * Every habit that has increment timestamps for the day appears as a block
 * anchored at its recorded time. Habits without time data are simply absent.
 * Tapping a block opens the timestamp editor popup (time, amount, text and,
 * for meals, the full meal editor).
 */
@Composable
fun ScheduleTimelineScreen(
    habitNames: List<String>,
    mealHabits: Set<String>,
    textInputHabits: Set<String>,
    /** Habits the user has excluded from the day timeline via edit mode. */
    timelineExcludedHabits: Set<String> = emptySet(),
    selectedDate: LocalDate,
    isToday: Boolean,
    /** Bumped by the host whenever the editor popup closes, to reload. */
    refreshTrigger: Int,
    timestampRepo: HabitTimestampRepository,
    onEventClick: (habitName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var events by remember { mutableStateOf<List<ScheduleEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Load all timestamps for the selected day, grouped per habit+moment.
    LaunchedEffect(selectedDate, refreshTrigger) {
        loading = true
        val all = timestampRepo.loadAll()
        val dateKey = dateString(selectedDate)
        val known = habitNames.filter { it.isNotEmpty() }.toSet()
        events = all
            .filterKeys { it in known && it !in timelineExcludedHabits }
            .mapNotNull { (habit, days) ->
                days[dateKey]?.takeIf { it.isNotEmpty() }?.let { habit to it }
            }
            .flatMap { (habit, times) ->
                times.groupBy { it }.map { (time, group) ->
                    ScheduleEvent(
                        habitName = habit,
                        time = time,
                        amount = group.size,
                        isMeal = habit in mealHabits,
                        canEditText = habit in textInputHabits
                    )
                }
            }
            .sortedWith(compareBy({ it.minuteOfDay }, { it.habitName }))
        loading = false
    }

    // Live "now" minute for the indicator line (today only).
    var nowMinute by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(isToday) {
        while (isToday) {
            nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
            delay(30_000)
        }
    }

    val listState = rememberLazyListState()
    // Jump to a sensible hour on open: the current hour today, otherwise
    // the first event (or a morning default).
    LaunchedEffect(loading, isToday) {
        if (loading) return@LaunchedEffect
        val targetHour = when {
            isToday -> (nowMinute / 60).coerceAtLeast(0)
            events.isNotEmpty() -> events.first().minuteOfDay / 60
            else -> 8
        }
        listState.animateScrollToItem(targetHour)
    }

    val distinctHabits = remember(events) { events.map { it.habitName }.distinct().size }
    val firstTime = events.firstOrNull()?.time?.take(5)
    val lastTime = events.lastOrNull()?.time?.take(5)

    Column(modifier = modifier.fillMaxSize()) {
        // ── Summary header ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = Color(0xFF66CCFF),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    loading -> "Building your day…"
                    events.isEmpty() -> "No timed activity"
                    else -> "${
                        if (distinctHabits == 1) "1 habit" else "$distinctHabits habits"
                    } · ${events.size} event${if (events.size != 1) "s" else ""}" +
                        (if (firstTime != null && lastTime != null) " · $firstTime–$lastTime" else "")
                },
                fontSize = 12.sp,
                color = Color(0xFF999999),
                fontWeight = FontWeight.Medium
            )
        }

        if (!loading && events.isEmpty()) {
            // ── Empty state ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color(0xFF444444),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Nothing on the clock",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF888888)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Habits you logged with a time will appear here",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        } else {
            // ── Hour-by-hour timeline ─────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed((0..23).toList()) { _, hour ->
                    HourRow(
                        hour = hour,
                        events = events.filter { it.minuteOfDay / 60 == hour },
                        isNowHour = isToday && nowMinute / 60 == hour,
                        nowMinuteInHour = if (isNowHourNow(isToday, nowMinute, hour)) nowMinute % 60 else null,
                        onEventClick = onEventClick
                    )
                }
            }
        }
    }
}

private fun isNowHourNow(isToday: Boolean, nowMinute: Int, hour: Int): Boolean =
    isToday && nowMinute / 60 == hour

/**
 * One hour strip of the timeline: a gutter label plus the events that fall
 * inside this hour, positioned at their exact minute and laid out into
 * side-by-side lanes when they overlap.
 */
@Composable
private fun HourRow(
    hour: Int,
    events: List<ScheduleEvent>,
    isNowHour: Boolean,
    nowMinuteInHour: Int?,
    onEventClick: (String) -> Unit
) {
    val hasEvents = events.isNotEmpty()
    Box(modifier = Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
        // ── Gutter: hour label + tick ────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(GUTTER_WIDTH)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            if (hasEvents) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .background(Color(0xFF555555), RoundedCornerShape(1.5.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = "%02d:00".format(hour),
                fontSize = 10.sp,
                fontWeight = if (hasEvents) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isNowHour -> Color(0xFF66CCFF)
                    hasEvents -> Color(0xFFBBBBBB)
                    else -> Color(0xFF555555)
                },
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        // ── Vertical grid line for the hour ──────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = GUTTER_WIDTH)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    when {
                        isNowHour -> Color(0xFF66CCFF).copy(alpha = 0.35f)
                        hasEvents -> Color(0xFF3A3A3A)
                        else -> Color(0xFF242424)
                    }
                )
        )

        // ── Event chips ──────────────────────────────────────────────────
        // Assign lanes: chips within ~28 minutes of each other go side by
        // side. Each lane is an equal-weight column; chips are vertically
        // offset to their exact minute within the hour.
        val laneEndMinutes = mutableListOf<Int>()
        val positioned = events.map { event ->
            val start = event.minuteOfDay % 60
            val lane = laneEndMinutes.indexOfFirst { it <= start }
            val assigned = if (lane >= 0) {
                laneEndMinutes[lane] = start + 28
                lane
            } else {
                laneEndMinutes.add(start + 28)
                laneEndMinutes.lastIndex
            }
            event to (start to assigned)
        }
        val laneCount = laneEndMinutes.size.coerceIn(1, 3)

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = GUTTER_WIDTH + 4.dp)
                .fillMaxWidth()
                .height(HOUR_HEIGHT)
        ) {
            repeat(laneCount) { laneIdx ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                ) {
                    positioned
                        .filter { it.second.second == laneIdx }
                        .forEach { (event, minuteAndLane) ->
                            val (startMinute, _) = minuteAndLane
                            EventChip(
                                event = event,
                                topOffset = HOUR_HEIGHT * (startMinute / 60f) - EVENT_HEIGHT / 2,
                                onClick = { onEventClick(event.habitName) },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                }
            }
        }

        // ── Now indicator ────────────────────────────────────────────────
        if (nowMinuteInHour != null) {
            val y = HOUR_HEIGHT * (nowMinuteInHour / 60f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = GUTTER_WIDTH - 4.dp, y = y)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF66CCFF))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = GUTTER_WIDTH, y = y + 3.5.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF66CCFF).copy(alpha = 0.6f))
            )
        }
    }
}

/**
 * A single timed-occurrence chip on the timeline: tinted with the habit's
 * stable accent colour, showing the habit name, the ×amount for
 * multi-increments, the time, and a 🍽 marker for meal habits.
 */
@Composable
private fun EventChip(
    event: ScheduleEvent,
    topOffset: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = scheduleAccentFor(event.habitName)
    Box(
        modifier = modifier
            .offset(y = topOffset)
            .fillMaxWidth()
            .height(EVENT_HEIGHT)
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        // Accent stripe on the left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(vertical = 4.dp)
                .size(width = 3.dp, height = EVENT_HEIGHT - 8.dp)
                .background(accent, RoundedCornerShape(1.5.dp))
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = event.habitName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEAEAEA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (event.amount > 1) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "×${event.amount}",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = event.time.take(5),
                fontSize = 9.sp,
                color = Color(0xFF999999),
                maxLines = 1
            )
            if (event.isMeal) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = "🍽", fontSize = 9.sp)
            }
        }
    }
}

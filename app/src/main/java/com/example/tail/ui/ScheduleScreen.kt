package com.example.tail.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.dateString
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil

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
    val canEditText: Boolean,
    /**
     * Watch-duration minutes for movie-bridge entries (the "(N min)"
     * annotation on the habit's text entry); 0 when the event carries no
     * length. A block grows to cover this much time on the timeline.
     */
    val durationMinutes: Int = 0
) {
    val minuteOfDay: Int by lazy {
        runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.NOON).let { it.hour * 60 + it.minute }
    }
}

/**
 * One rendered rectangle on the timeline. A block with a single event is
 * the usual "one timestamp" chip; a block with several events merges a
 * cluster of same-habit timestamps recorded close together (e.g. a run of
 * chess games) into one rectangle spanning the whole cluster, labelled
 * with the total ×count.
 */
data class ScheduleBlock(
    val habitName: String,
    /** Minute of day of the first timestamp in the block. */
    val startMinute: Int,
    /** Minute of day the block visually ends at: last timestamp + minimum span. */
    val endMinute: Int,
    /** Total increment units across all merged events. */
    val amount: Int,
    /** Number of distinct timestamps merged into this block. */
    val eventCount: Int,
    /** "HH:mm:ss" of the first timestamp in the block. */
    val firstTime: String,
    /** "HH:mm:ss" of the last timestamp in the block. */
    val lastTime: String,
    val isMeal: Boolean,
    val canEditText: Boolean
) {
    /** Timeline minutes the block occupies (always ≥ [MIN_SPAN_MINUTES]). */
    val spanMinutes: Int get() = endMinute - startMinute
}

/** Vertical size of one hour on the timeline. */
private val HOUR_HEIGHT = 64.dp
/** Width of the hour-label gutter on the left. */
private val GUTTER_WIDTH = 46.dp
/**
 * Minimum visual height of an event chip — just enough to comfortably fit
 * its single line of text. Habits whose timestamps spread over more time
 * get proportionally taller blocks.
 */
private val EVENT_MIN_HEIGHT = 22.dp
/**
 * Timeline minutes covered by the minimum chip height: 22dp at 64dp/hour
 * ≈ 20.6 → 21 minutes. Every block occupies at least this much time.
 */
internal val MIN_SPAN_MINUTES = ceil(EVENT_MIN_HEIGHT.value / HOUR_HEIGHT.value * 60f).toInt()
/**
 * Blocks spanning at least this much time are tall enough to stack the time
 * label under the habit name (two text lines ≈ 28dp; 36 min ≈ 38dp).
 */
private const val TWO_LINE_SPAN_MINUTES = 36
/**
 * Maximum rectangle width. Chips hug their content — the full habit name
 * plus its labels — and only stop growing at this cap, where the name
 * ellipsizes. Nothing is padded out to a shared width.
 */
private val CHIP_WIDTH = 150.dp
/** Horizontal gap between chips packed next to each other in time. */
private val CHIP_SPACING = 8.dp
/**
 * Same-habit timestamps at most this many minutes apart are treated as one
 * continuous session and merged into a single block. Chains keep merging
 * while each consecutive gap stays within the threshold, so a morning of
 * back-to-back chess games collapses into one tall rectangle with ×N
 * instead of a lane full of identical chips.
 */
internal const val MERGE_GAP_MINUTES = 30

/**
 * Merges the day's events into renderable blocks: per habit, consecutive
 * timestamps whose gap is ≤ [MERGE_GAP_MINUTES] collapse into one block
 * spanning from the first to the last timestamp (plus the minimum chip
 * span). The result is sorted by start time, then habit name.
 */
fun buildScheduleBlocks(events: List<ScheduleEvent>): List<ScheduleBlock> {
    val blocks = mutableListOf<ScheduleBlock>()
    for ((habit, habitEvents) in events.groupBy { it.habitName }) {
        val sorted = habitEvents.sortedBy { it.minuteOfDay }
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size &&
                sorted[j + 1].minuteOfDay - sorted[j].minuteOfDay <= MERGE_GAP_MINUTES
            ) j++
            val group = sorted.subList(i, j + 1)
            val start = group.first().minuteOfDay
            blocks += ScheduleBlock(
                habitName = habit,
                startMinute = start,
                // A block ends at the latest of its events' ends: every
                // event occupies at least the minimum chip span, or its
                // full watch duration (movie entries) when that is longer.
                endMinute = group.maxOf {
                    it.minuteOfDay + maxOf(MIN_SPAN_MINUTES, it.durationMinutes)
                },
                amount = group.sumOf { it.amount },
                eventCount = group.size,
                firstTime = group.first().time,
                lastTime = group.last().time,
                isMeal = group.first().isMeal,
                canEditText = group.first().canEditText
            )
            i = j + 1
        }
    }
    return blocks.sortedWith(compareBy({ it.startMinute }, { it.habitName }))
}

/**
 * Harmonious accent palette for event chips. A habit's colour encodes the
 * habits screen (grid tab) it belongs to, so everything from one screen
 * shares a colour; the palette cycles when there are more screens than
 * colours.
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

/**
 * Stable accent colour for a habit. With a known [screenIndex] (the habits
 * screen/grid tab the habit lives on) the colour encodes that screen;
 * otherwise it falls back to a stable name-derived colour.
 */
fun scheduleAccentFor(habitName: String, screenIndex: Int? = null): Color =
    SCHEDULE_PALETTE[Math.floorMod(screenIndex ?: habitName.hashCode(), SCHEDULE_PALETTE.size)]

/**
 * The Daily Schedule — a retrospective, hour-by-hour timeline of what was
 * actually done on the selected day.
 *
 * Every habit that has increment timestamps for the day appears as a block
 * anchored at its recorded time and sized to the time it spans: a minimum
 * one-liner height for isolated timestamps, growing to cover the full
 * duration of same-habit clusters (merged into one block with a ×count).
 * Movie-bridge blocks instead cover their watched duration — the "(N min)"
 * length annotated on the entry. Each block is tinted with the colour of
 * the habits screen (grid tab) its habit lives on. Habits without time
 * data are simply absent. Tapping a block opens the
 * timestamp editor popup (time, amount, text and, for meals, the full
 * meal editor).
 */
@Composable
fun ScheduleTimelineScreen(
    habitNames: List<String>,
    mealHabits: Set<String>,
    textInputHabits: Set<String>,
    /** Habits the user has excluded from the day timeline via edit mode. */
    timelineExcludedHabits: Set<String> = emptySet(),
    /** Movie-bridge habits whose text entries carry "(N min)" watch lengths. */
    movieHabits: Set<String> = emptySet(),
    /**
     * Loads a movie habit's entries for the selected day from its text
     * log: watch time-of-day ("HH:mm:ss") → watched minutes (0 when the
     * entry has no "(N min)" length yet). Text-log timestamps are the
     * source of truth for movies, so past films appear even without
     * increment timestamps.
     */
    loadMovieEntries: suspend (habitName: String) -> Map<String, Int> = { emptyMap() },
    /** Habits linked to Garmin metrics (activity blocks are sized to the activity). */
    garminHabits: Set<String> = emptySet(),
    /**
     * Loads the watch-recorded start time ("HH:mm:ss", null when unknown)
     * and duration minutes of the Garmin activity linked to a habit on
     * the selected day; null when the habit has no activity data.
     */
    loadGarminActivity: suspend (habitName: String) -> Pair<String?, Int>? = { null },
    /** Index of the habits screen (grid tab) each habit belongs to — drives chip colour. */
    screenIndexOfHabit: Map<String, Int> = emptyMap(),
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
        // Movie habits: entries come from the TEXT LOG — every entry has
        // its watch time, and the "(N min)" length sizes the block — so
        // past films appear even when no increment timestamp exists.
        if (movieHabits.isNotEmpty()) {
            val merged = events.toMutableList()
            for (habit in movieHabits) {
                if (habit !in known || habit in timelineExcludedHabits) continue
                val entries = loadMovieEntries(habit)
                if (entries.isNotEmpty()) {
                    // The text log is authoritative for movie habits: drop
                    // increment-only events (e.g. a confirm-time stamp) so
                    // each film has exactly ONE block, at its watch-start
                    // time — never two separate times for one movie.
                    merged.removeAll { event ->
                        event.habitName == habit &&
                            entries.keys.none { it.take(5) == event.time.take(5) }
                    }
                }
                for ((time, minutes) in entries) {
                    val idx = merged.indexOfFirst {
                        it.habitName == habit && it.time.take(5) == time.take(5)
                    }
                    if (idx >= 0) {
                        if (minutes > merged[idx].durationMinutes) {
                            merged[idx] = merged[idx].copy(durationMinutes = minutes)
                        }
                    } else {
                        merged += ScheduleEvent(
                            habitName = habit,
                            time = time,
                            amount = 1,
                            isMeal = habit in mealHabits,
                            canEditText = habit in textInputHabits,
                            durationMinutes = minutes
                        )
                    }
                }
            }
            events = merged
        }
        // Garmin-linked activity habits: place the block at the
        // watch-recorded start time and size it to the activity's
        // minutes, so past runs/rides/swims show at their real time
        // and duration.
        if (garminHabits.isNotEmpty()) {
            val merged = events.toMutableList()
            for (habit in garminHabits) {
                if (habit !in known || habit in timelineExcludedHabits) continue
                val (startTime, minutes) = loadGarminActivity(habit) ?: continue
                if (minutes <= 0) continue
                val idx = merged.indexOfFirst {
                    it.habitName == habit &&
                        (startTime == null || it.time.take(5) == startTime.take(5))
                }
                if (idx >= 0) {
                    merged[idx] = merged[idx].copy(durationMinutes = minutes)
                } else if (startTime != null) {
                    merged += ScheduleEvent(
                        habitName = habit,
                        time = startTime,
                        amount = 1,
                        isMeal = habit in mealHabits,
                        canEditText = habit in textInputHabits,
                        durationMinutes = minutes
                    )
                }
            }
            events = merged
        }
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

    // Shared horizontal scroll for the event chips: the whole timeline uses
    // one state so the packed chips pan together when they are together
    // wider than the screen (chips hug their habit names, so widths vary).
    val chipScroll = rememberScrollState()
    val timelineScroll = rememberScrollState()
    val density = LocalDensity.current
    // Jump to a sensible hour on open: the current hour today, otherwise
    // the first event (or a morning default).
    LaunchedEffect(loading, isToday) {
        if (loading) return@LaunchedEffect
        val targetHour = when {
            isToday -> (nowMinute / 60).coerceAtLeast(0)
            events.isNotEmpty() -> events.first().minuteOfDay / 60
            else -> 8
        }
        val targetPx = with(density) { (HOUR_HEIGHT * targetHour).roundToPx() }
        timelineScroll.animateScrollTo(targetPx)
    }

    // ── Merged blocks ─────────────────────────────────────────────────────
    // Events are merged per habit into duration blocks (see
    // buildScheduleBlocks); the chip layout below left-packs them
    // horizontally by their real time overlaps.
    val blocks = remember(events) { buildScheduleBlocks(events) }

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
            // One continuous 24h strip (not a lazy list) so blocks taller
            // than one hour can extend across hour boundaries and stay
            // composed no matter where the viewport is.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(timelineScroll)
            ) {
                // ── Backdrop: hour gutter labels + grid lines ─────────────
                Column {
                    repeat(24) { hour ->
                        HourBackdrop(
                            hour = hour,
                            hasEvents = blocks.any {
                                it.startMinute < (hour + 1) * 60 && it.endMinute > hour * 60
                            },
                            isNowHour = isToday && nowMinute / 60 == hour
                        )
                    }
                }

                // ── Event blocks overlay ──────────────────────────────────
                // Blocks are positioned at their absolute time over the full
                // 24h strip and lane-packed horizontally: each chip takes
                // the leftmost x that clears every chip it overlaps in time
                // (the far left is tried first), so a chain of slightly-
                // overlapping habits reuses lanes freed on the left instead
                // of staircasing endlessly to the right. When the packed
                // chips together exceed the screen width the shared
                // horizontal scroll pans the chip area — the hour gutter and
                // grid lines stay pinned. Drawn after the backdrop so blocks
                // sit on top of the grid lines they cover.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        // padding (not offset) so the scroll viewport ends at
                        // the screen edge — with offset the viewport overhung
                        // the screen by the gutter width and max-scroll
                        // stopped that much short of the last chip's right
                        // edge.
                        .padding(start = GUTTER_WIDTH + 4.dp)
                        .horizontalScroll(chipScroll)
                ) {
                    Layout(
                        content = {
                            blocks.forEach { block ->
                                EventChip(
                                    block = block,
                                    accent = scheduleAccentFor(
                                        block.habitName,
                                        screenIndexOfHabit[block.habitName]
                                    ),
                                    height = HOUR_HEIGHT * (block.spanMinutes / 60f),
                                    onClick = { onEventClick(block.habitName) },
                                    modifier = Modifier.layoutId(block)
                                )
                            }
                        }
                    ) { measurables, _ ->
                        val chipMaxWidth = CHIP_WIDTH.roundToPx()
                        val spacing = CHIP_SPACING.roundToPx()
                        val placeables = measurables.map { measurable ->
                            val block = measurable.layoutId as ScheduleBlock
                            block to measurable.measure(
                                Constraints(
                                    minWidth = 0,
                                    maxWidth = chipMaxWidth,
                                    minHeight = 0,
                                    maxHeight = Constraints.Infinity
                                )
                            )
                        }
                        // Lane-packed leftmost-fit in start-time order
                        // (blocks are already sorted). Placing each chip
                        // right after the rightmost overlapping chip makes a
                        // chain of slightly-overlapping habits staircase to
                        // the right even when the far-left lane is free
                        // again. Instead, the already-placed chips this one
                        // overlaps in time are obstacles, and the chip takes
                        // the leftmost candidate x — 0 first, then just
                        // right of every obstacle — whose width clears them
                        // all. The rightmost candidate always fits, so a
                        // position is guaranteed.
                        val xs = IntArray(placeables.size)
                        for (i in placeables.indices) {
                            val (block, placeable) = placeables[i]
                            // x-ranges (padded with trailing spacing) of the
                            // placed chips this one overlaps in time
                            val obstacles = (0 until i).mapNotNull { j ->
                                val (other, otherPlaceable) = placeables[j]
                                if (other.endMinute > block.startMinute) {
                                    xs[j] to xs[j] + otherPlaceable.width + spacing
                                } else null
                            }
                            val candidates =
                                (listOf(0) + obstacles.map { it.second })
                                    .distinct().sorted()
                            xs[i] = candidates.first { cand ->
                                obstacles.all { (left, right) ->
                                    cand + placeable.width + spacing <= left ||
                                        cand >= right
                                }
                            }
                        }
                        // Trailing breathing room so the final chip fully
                        // clears the screen edge at maximum scroll.
                        val contentWidth =
                            (placeables.indices.maxOfOrNull {
                                xs[it] + placeables[it].second.width
                            } ?: 0) + spacing
                        layout(contentWidth, (HOUR_HEIGHT * 24).roundToPx()) {
                            placeables.forEachIndexed { i, (block, placeable) ->
                                placeable.placeRelative(
                                    xs[i],
                                    (HOUR_HEIGHT * (block.startMinute / 60f)).roundToPx()
                                )
                            }
                        }
                    }
                }

                // ── Now indicator ─────────────────────────────────────────
                if (isToday) {
                    val y = HOUR_HEIGHT * (nowMinute / 60f)
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
    }
}

/**
 * One hour strip of the timeline backdrop: the gutter label with its tick
 * and the horizontal grid line at the top of the hour.
 */
@Composable
private fun HourBackdrop(
    hour: Int,
    hasEvents: Boolean,
    isNowHour: Boolean
) {
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
    }
}

/**
 * A single block on the timeline: tinted with the colour of the habits
 * screen its habit belongs to, sized to the time it spans (minimum one
 * text-height; movie blocks cover their watched duration), showing the
 * habit name, the ×count (merged units or multi-increments), the time —
 * or the first–last range for merged clusters — and a 🍽 marker for meal
 * habits. Blocks tall enough to afford it stack the ×count and time
 * labels under the name (a long name would otherwise squeeze the ×count
 * out of the name row). Every chip hugs its content — the full habit
 * name is shown whenever it fits — and only stops growing at
 * [CHIP_WIDTH], where the name ellipsizes.
 */
@Composable
private fun EventChip(
    block: ScheduleBlock,
    accent: Color,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Tall blocks (spanning real time) stack the ×count + time labels
    // under the name, which frees horizontal space for a longer name;
    // one-line blocks keep the compact side-by-side label.
    val twoLine = block.spanMinutes >= TWO_LINE_SPAN_MINUTES
    val timeLabel = if (block.eventCount > 1) {
        "${block.firstTime.take(5)}–${block.lastTime.take(5)}"
    } else {
        block.firstTime.take(5)
    }
    Box(
        modifier = modifier
            .height(height)
            // Hug the content — full habit name plus labels — and only
            // stop growing at the shared cap, where the name ellipsizes.
            .widthIn(max = CHIP_WIDTH)
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        // Accent stripe on the left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(vertical = 4.dp)
                .fillMaxHeight()
                .width(3.dp)
                .background(accent, RoundedCornerShape(1.5.dp))
        )
        if (twoLine) {
            // ── Tall block: name on top, ×count + time underneath ───────
            // The ×count lives on the bottom row: a long name (e.g.
            // "Programming sessions") fills the name row to the chip cap,
            // and a trailing ×count there gets squeezed to zero width and
            // soft-wraps onto its own line.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp, end = 6.dp)
            ) {
                Text(
                    text = block.habitName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEAEAEA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (block.amount > 1) {
                        Text(
                            text = "×${block.amount}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = timeLabel,
                        fontSize = 9.sp,
                        color = Color(0xFF999999),
                        maxLines = 1
                    )
                    if (block.isMeal) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = "🍽", fontSize = 9.sp)
                    }
                }
            }
        } else {
            // ── One-line block: name, ×count and time side by side ──────
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.habitName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEAEAEA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Grow into whatever room is left inside the chip cap;
                    // the chip still hugs its content when the name fits.
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (block.amount > 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "×${block.amount}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = timeLabel,
                    fontSize = 9.sp,
                    color = Color(0xFF999999),
                    maxLines = 1
                )
                if (block.isMeal) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "🍽", fontSize = 9.sp)
                }
            }
        }
    }
}

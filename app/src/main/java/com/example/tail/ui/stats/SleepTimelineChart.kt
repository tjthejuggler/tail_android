package com.example.tail.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.MINUTES_PER_DAY
import com.example.tail.data.minutesToClockString
import com.example.tail.data.parseDate
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.SleepSession
import com.example.tail.ui.viewmodel.loadSleepSessions
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Timeline-style sleep graph: one horizontal bar per night, drawn on a
 * 24-hour clock axis that WRAPS at midnight (16:00 → 16:00) so a 23:30–07:15
 * session renders as one continuous band instead of two fragments.
 *
 * Data comes from the MERGED [SleepSession] list (bed half + wake half +
 * wake survey), which is the graph for BOTH suite halves at once: tapping
 * either the sleep-time or the wake-time habit opens this panel, and the
 * sessions include stats from both.
 *
 * Drawn as a Canvas (same rendering approach as [HabitLineChart]) with a
 * scrollable stats section below — average bedtime, average wake time,
 * average duration, average temperature, total awakenings, awake time,
 * average quality — plus a per-session tooltip on tap.
 */
@Composable
fun SleepTimelineChart(
    sessions: List<SleepSession>,
    startDate: LocalDate,
    endDate: LocalDate,
    modifier: Modifier = Modifier,
    onDateSelected: ((LocalDate) -> Unit)? = null
) {
    val bedColor = Color(0xFF7986CB)       // indigo — sleep band
    val wakeColor = Color(0xFFFFB74D)      // amber — wake marker
    val missingColor = Color(0xFF3A3A4A)   // bars with only one half logged
    val gridColor = Color(0xFF2A2A35)
    val textColor = Color(0xFF99A0B5)

    // Session selected by tapping a bar (null = none).
    var selected by remember { mutableStateOf<SleepSession?>(null) }

    // Days spanning the window (one row per day).
    val days = remember(startDate, endDate) {
        generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .toList()
    }

    val sessionsByDate = remember(sessions) { sessions.associateBy { it.date } }

    // Axis wraps: midnight sits in the MIDDLE of the chart, 16:00 on both ends.
    // A minute m maps to x = ((m - 960 + 1440) % 1440) / 1440 — bedtime in the
    // evening maps left of centre, wake time right of centre.
    val AXIS_START = 16 * 60 // 16:00

    // Newest dates first, and leading empty days trimmed: days before the
    // earliest session (i.e. before ANY data existed) are dropped, while
    // empty days AFTER data started (missed days) still render.
    val firstDataDate = sessions.minOfOrNull { it.date }
    val displayStart = firstDataDate?.let { maxOf(startDate, it) } ?: startDate
    val displayDays = days.filter { !it.isBefore(displayStart) }.asReversed()

    Column(modifier = modifier) {
        // ── Hour labels along the top (every 4 h on the wrapped axis) ──
        // Insets mirror the row layout below: 52dp = date-label gutter,
        // 110dp = summary-text column, so labels sit exactly over the bars.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 110.dp, top = 0.dp, bottom = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(16, 20, 0, 4, 8, 12, 16).forEach { h ->
                Text(
                    text = "%02d".format(h),
                    color = textColor,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── One aligned row PER DAY: date label + that day's bar canvas +
        // summary text all share the same 24dp Row, so bars can never drift
        // out of sync with their dates regardless of window length.
        Column {
            displayDays.forEach { day ->
                val s = sessionsByDate[day]
                val rowSelected = selected?.date == day
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            selected = if (rowSelected) null else s
                            onDateSelected?.invoke(day)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Date label
                    Text(
                        text = day.format(DateTimeFormatter.ofPattern("EEE dd")),
                        color = if (rowSelected) Color(0xFFE0E4FF) else textColor,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 8.dp).width(44.dp)
                    )
                    // This day's bar (single-row canvas keeps bar & label locked)
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                    ) {
                        val barH = size.height
                        val axisW = size.width
                        val y = 0f

                        // Vertical grid lines every 4 h on the wrapped axis
                        for (h in listOf(16, 20, 0, 4, 8, 12)) {
                            val x = axisW * (((h * 60 - AXIS_START + MINUTES_PER_DAY) % MINUTES_PER_DAY).toFloat() / MINUTES_PER_DAY)
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f
                            )
                        }

                        if (s == null) return@Canvas
                        if (s.segments.isNotEmpty()) {
                            // One band PER bed→wake segment.
                            var first = true
                            s.segments.forEach { seg ->
                                val startF = (((seg.bed - AXIS_START + MINUTES_PER_DAY) % MINUTES_PER_DAY).toFloat()) / MINUTES_PER_DAY
                                val lenF = seg.durationMin.toFloat() / MINUTES_PER_DAY
                                drawRoundRect(
                                    color = bedColor,
                                    topLeft = Offset(startF * axisW, y),
                                    size = Size(lenF * axisW, barH),
                                    cornerRadius = CornerRadius(barH / 3f)
                                )
                                drawCircle(
                                    color = wakeColor,
                                    radius = barH / 3.2f,
                                    center = Offset((startF + lenF) * axisW, y + barH / 2)
                                )
                                // Quality number at the START of the first band
                                // (replaces the old quality pips).
                                val q = s.quality
                                if (q != null && first) {
                                    drawIntoCanvas { canvas ->
                                        val paint = android.graphics.Paint().apply {
                                            isAntiAlias = true
                                            color = android.graphics.Color.WHITE
                                            textSize = barH * 0.62f
                                            textAlign = android.graphics.Paint.Align.LEFT
                                        }
                                        val text = q.toString()
                                        val tw = paint.measureText(text)
                                        canvas.nativeCanvas.drawText(
                                            text,
                                            (startF * axisW + 4f).coerceAtMost(size.width - tw - 2f),
                                            y + barH / 2f - (paint.ascent() + paint.descent()) / 2f,
                                            paint
                                        )
                                    }
                                }
                                first = false
                            }
                        } else {
                            // Only one half logged — small pill at its minute.
                            val m = s.bed ?: s.wake ?: return@Canvas
                            val f = (((m - AXIS_START + MINUTES_PER_DAY) % MINUTES_PER_DAY).toFloat()) / MINUTES_PER_DAY
                            drawRoundRect(
                                color = missingColor,
                                topLeft = Offset(f * axisW - barH, y),
                                size = Size(barH * 2, barH),
                                cornerRadius = CornerRadius(barH / 3f)
                            )
                            if (s.wake != null) {
                                drawCircle(
                                    color = wakeColor,
                                    radius = barH / 3.2f,
                                    center = Offset(f * axisW + barH, y + barH / 2)
                                )
                            }
                        }
                    }
                    // Summary text (bed → wake, duration)
                    Text(
                        text = listOfNotNull(
                            s?.bed?.let { minutesToClockString(it) },
                            s?.wake?.let { "→ ${minutesToClockString(it)}" },
                            s?.durationMin?.let { formatSleepDuration(it) }
                        ).joinToString("  "),
                        color = Color(0xFF8899AA),
                        fontSize = 9.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp, end = 8.dp).widthIn(min = 96.dp)
                    )
                }
            }
        }

        // ── Selected session detail ──
        selected?.let { s ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Night of ${s.date.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))}",
                    color = Color(0xFFE0E4FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val lines = (
                    if (s.segments.size > 1) {
                        // Multi-segment night: show every bed→wake pair.
                        s.segments.mapIndexed { i, seg ->
                            "Sleep ${i + 1}: ${minutesToClockString(seg.bed)} → " +
                                "${minutesToClockString(seg.wake)} (${formatSleepDuration(seg.durationMin)})"
                        } + listOfNotNull(
                            s.durationMin?.let { "Total: ${formatSleepDuration(it)}" }
                        )
                    } else {
                        listOfNotNull(
                            s.bed?.let { "Bed: ${minutesToClockString(it)}" },
                            s.wake?.let { "Wake: ${minutesToClockString(it)}" },
                            s.durationMin?.let { "Duration: ${formatSleepDuration(it)}" }
                        )
                    }
                ) + listOfNotNull(
                    s.tempTenths?.let { "Temp: ${it / 10.0}°C" },
                    s.conditions?.takeIf { it.isNotBlank() }?.let { "Conditions: $it" },
                    s.awakenings?.let { "Awakenings: $it" },
                    s.awakeMin?.let { "Time awake: ${formatSleepDuration(it)}" },
                    s.quality?.let { "Quality: $it/10" }
                )
                lines.forEach {
                    Text(text = it, color = Color(0xFFAAB4CC), fontSize = 11.sp)
                }
            }
        }

        // ── Aggregate stats over the window ──
        val withBoth = sessions.filter { it.durationMin != null }
        if (withBoth.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            SleepStatsGrid(sessions = withBoth, windowSessions = sessions)
        }
    }
}

/**
 * The "other stats and information" block under the timeline: averages and
 * totals for the visible window, rendered as a 2-column grid of chips.
 */
@Composable
private fun SleepStatsGrid(
    sessions: List<SleepSession>,
    windowSessions: List<SleepSession>
) {
    val avgBed = windowSessions.mapNotNull { it.bed }.average().takeIf { !it.isNaN() }
    val avgWake = windowSessions.mapNotNull { it.wake }.average().takeIf { !it.isNaN() }
    val avgDur = sessions.mapNotNull { it.durationMin }.average().takeIf { !it.isNaN() }
    val avgTemp = sessions.mapNotNull { it.tempTenths }.average().takeIf { !it.isNaN() }
    val avgQual = sessions.mapNotNull { it.quality }.average().takeIf { !it.isNaN() }
    val totalAwakenings = sessions.sumOf { it.awakenings ?: 0 }
    val totalAwakeMin = sessions.sumOf { it.awakeMin ?: 0 }
    val nights = sessions.size

    val stats = listOfNotNull(
        SleepStat("Nights", "$nights"),
        avgBed?.let { SleepStat("Avg bed", minutesToClockString(it.toInt())) },
        avgWake?.let { SleepStat("Avg wake", minutesToClockString(it.toInt())) },
        avgDur?.let { SleepStat("Avg duration", formatSleepDuration(it.toInt())) },
        avgTemp?.let { SleepStat("Avg temp", "%.1f°C".format(it / 10.0)) },
        if (totalAwakenings > 0) SleepStat("Awakenings", "$totalAwakenings") else null,
        if (totalAwakeMin > 0) SleepStat("Time awake", formatSleepDuration(totalAwakeMin)) else null,
        avgQual?.let { SleepStat("Avg quality", "%.1f/10".format(it)) }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151522), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stats.chunked(2).forEach { rowStats ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowStats.forEach { st ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = st.value,
                            color = Color(0xFFB3CFFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(text = st.label, color = Color(0xFF777788), fontSize = 10.sp)
                    }
                }
                if (rowStats.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private data class SleepStat(val label: String, val value: String)

/** "h:mm" duration formatting shared by the graph rows and the stats grid. */
internal fun formatSleepDuration(minutes: Int): String = "%dh%02d".format(minutes / 60, minutes % 60)

/**
 * Remembers-and-loads the merged sleep sessions for the current window.
 * Extracted so the GraphsPanel call site stays small.
 */
@Composable
fun rememberSleepSessions(
    viewModel: HabitViewModel,
    sleepHabits: List<String>,
    wakeHabits: List<String>,
    startDate: LocalDate,
    endDate: LocalDate,
    refreshKey: Any?
): List<SleepSession> {
    var sessions by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    LaunchedEffect(sleepHabits, wakeHabits, startDate, endDate, refreshKey) {
        if (sleepHabits.isEmpty() && wakeHabits.isEmpty()) {
            sessions = emptyList()
        } else {
            viewModel.loadSleepSessions(sleepHabits, wakeHabits, startDate, endDate) {
                sessions = it
            }
        }
    }
    return sessions
}

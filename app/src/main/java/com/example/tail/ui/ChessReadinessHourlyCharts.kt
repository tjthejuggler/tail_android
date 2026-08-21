package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.GameFilter
import com.example.tail.data.GameSpeed
import com.example.tail.data.HourlyReadiness
import com.example.tail.data.HourlyWinRate
import com.example.tail.data.ReadinessGameRecord
import com.example.tail.data.ReadinessTestRecord
import com.example.tail.data.computeHourlyWinRates
import java.time.ZoneId
import kotlin.math.roundToInt

// ── Local palette (matches the readiness stats screen's warm look) ───────────
private val PopupBg = Color(0xFF0D0D1A)
private val PopupTitleColor = Color(0xFFFFD700)
private val PopupLabelColor = Color(0xFFADD8E6)
private val PopupDimColor = Color(0xFF888888)
private val PopupGridColor = Color(0xFF1E1E30)
private val PopupOrange = Color(0xFFF2994A)
private val PopupGold = Color(0xFFFFC24D)
private val PopupYellow = Color(0xFFEAB308)
private val PopupGreen = Color(0xFF22C55E)
private val PopupRed = Color(0xFFEF4444)
private val ChipBgActive = Color(0xFF3A2A14)
private val ChipBgIdle = Color(0xFF1B140C)

/** Bar color for each game-filter subset, reused by chips and bars. */
internal fun gameFilterColor(filter: GameFilter): Color = when (filter) {
    GameFilter.ALL -> PopupOrange
    GameFilter.POST_TEST -> PopupYellow
    GameFilter.APPROVED -> PopupGreen
    GameFilter.UNAPPROVED -> PopupRed
}

/** Chip color for each speed option. */
private fun gameSpeedColor(speed: GameSpeed): Color = when (speed) {
    GameSpeed.ALL -> PopupGold
    GameSpeed.BULLET -> PopupRed
    GameSpeed.BLITZ -> PopupOrange
    GameSpeed.RAPID -> Color(0xFF6EC6FF)
}

// ── Shared controls ──────────────────────────────────────────────────────────

/**
 * Segmented All / Post-test / Approved / Unapproved toggle used by the
 * time-of-day section and the hourly win-rate popup. [counts] optionally
 * shows how many logged games each subset contains. Chips flow onto extra
 * lines when they don't fit one row (prevents the squeezed-chip wrapping
 * that used to leave a big empty gap in the section).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameFilterSelector(
    selected: GameFilter,
    onSelect: (GameFilter) -> Unit,
    counts: Map<GameFilter, Int> = emptyMap()
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GameFilter.entries.forEach { f ->
            val active = f == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) ChipBgActive else ChipBgIdle)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                val n = counts[f]
                Text(
                    f.label + if (n != null) " ($n)" else "",
                    color = if (active) gameFilterColor(f) else PopupLabelColor,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Segmented All speeds / Bullet / Blitz / Rapid toggle for the hourly
 * win-rate popup. [counts] optionally shows how many (subset-filtered)
 * games each speed contains.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameSpeedSelector(
    selected: GameSpeed,
    onSelect: (GameSpeed) -> Unit,
    counts: Map<GameSpeed, Int> = emptyMap()
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GameSpeed.entries.forEach { s ->
            val active = s == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) ChipBgActive else ChipBgIdle)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                val n = counts[s]
                Text(
                    s.label + if (n != null) " ($n)" else "",
                    color = if (active) gameSpeedColor(s) else PopupLabelColor,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Clickable "open chart" row used inside stats sections — title on the
 * left, orange underlined hint on the right (matches the existing
 * "Readiness Over Time" link style).
 */
@Composable
internal fun ChartLinkRow(
    title: String,
    hint: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = PopupLabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            hint,
            color = PopupOrange,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline
        )
    }
}

// ── Hourly bar canvas ────────────────────────────────────────────────────────

/** One drawable bar of the 24-hour chart. */
private class HourBar(
    val hour: Int,
    val hasData: Boolean,
    /** Bar height as a fraction of the plot area (0–1). */
    val frac: Float,
    /** Short value label drawn above the bar. */
    val topLabel: String,
    val color: Color
)

/**
 * Shared 24-bar canvas: gridlines + y labels, rounded bars with value
 * labels on top, hour labels below, an optional dashed average line, a
 * highlight outline on the selected hour, and tap-to-select.
 */
@Composable
private fun HourlyBarsCanvas(
    bars: List<HourBar>,
    maxValue: Float,
    /** Value of the dashed reference line in bar units (null = none). */
    avgValue: Float?,
    avgLabel: String,
    selectedHour: Int?,
    onTapHour: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val yLabelPx = with(density) { 9.dp.toPx() }
    val topPx = with(density) { 9.dp.toPx() }
    val hourPx = with(density) { 8.dp.toPx() }
    val yPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#7799AA")
        textSize = yLabelPx
        isAntiAlias = true
    }
    val topPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#ADD8E6")
        textSize = topPx
        isAntiAlias = true
        isFakeBoldText = true
    }
    val hourPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = hourPx
        isAntiAlias = true
    }
    val avgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFC24D")
        textSize = yLabelPx
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Pads shared by drawing and the tap hit-test.
    val padLDp = 34.dp
    val padRDp = 10.dp
    val padTDp = 16.dp
    val padBDp = 20.dp

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bars) {
                detectTapGestures { pos ->
                    val padL = with(density) { padLDp.toPx() }
                    val padR = with(density) { padRDp.toPx() }
                    val plotW = (size.width - padL - padR).coerceAtLeast(1f)
                    val slot = plotW / bars.size
                    val idx = ((pos.x - padL) / slot).toInt().coerceIn(0, bars.size - 1)
                    onTapHour(bars[idx].hour)
                }
            }
    ) {
        if (bars.isEmpty()) return@Canvas
        val padL = padLDp.toPx()
        val padR = padRDp.toPx()
        val padT = padTDp.toPx()
        val padB = padBDp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val bottom = h - padB

        // Gridlines + y labels (4 divisions)
        val steps = 4
        for (i in 0..steps) {
            val y = bottom - chartH * i / steps
            drawLine(PopupGridColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f)
            val v = (maxValue * i / steps).roundToInt().toString()
            drawContext.canvas.nativeCanvas.drawText(v, 0f, y + yLabelPx / 3, yPaint)
        }

        // Bars + labels
        val slot = chartW / bars.size
        val barW = slot * 0.62f
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        bars.forEachIndexed { i, bar ->
            val cx = padL + slot * i + slot / 2
            if (bar.hasData && bar.frac > 0f) {
                val bh = (chartH * bar.frac).coerceIn(2f, chartH)
                drawRoundRect(
                    color = bar.color,
                    topLeft = Offset(cx - barW / 2, bottom - bh),
                    size = androidx.compose.ui.geometry.Size(barW, bh),
                    cornerRadius = corner
                )
                // Value label above the bar
                topPaint.color = bar.color.toArgb()
                val tw = topPaint.measureText(bar.topLabel)
                drawContext.canvas.nativeCanvas.drawText(
                    bar.topLabel, cx - tw / 2, bottom - bh - 3.dp.toPx(), topPaint
                )
            }
            // Hour label below the axis
            val hl = bar.hour.toString()
            val hw = hourPaint.measureText(hl)
            hourPaint.color = if (bar.hour == selectedHour)
                android.graphics.Color.parseColor("#FFD700") else android.graphics.Color.parseColor("#888888")
            drawContext.canvas.nativeCanvas.drawText(
                hl, cx - hw / 2, bottom + hourPx + 4.dp.toPx(), hourPaint
            )
            // Selected-hour outline
            if (bar.hour == selectedHour && bar.hasData && bar.frac > 0f) {
                val bh = (chartH * bar.frac).coerceIn(2f, chartH)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(cx - barW / 2 - 2f, bottom - bh - 2f),
                    size = androidx.compose.ui.geometry.Size(barW + 4f, bh + 4f),
                    cornerRadius = corner,
                    style = Stroke(width = 1.5f)
                )
            }
        }

        // Dashed average reference line
        if (avgValue != null && maxValue > 0f) {
            val y = (bottom - chartH * (avgValue / maxValue)).coerceIn(padT, bottom)
            // drawLine has no path effect; draw small segments instead
            var x = padL
            while (x < w - padR) {
                val segEnd = minOf(x + 10f, w - padR)
                drawLine(PopupGold, Offset(x, y), Offset(segEnd, y), strokeWidth = 1.5f)
                x += 18f
            }
            val tw = avgPaint.measureText(avgLabel)
            drawContext.canvas.nativeCanvas.drawText(
                avgLabel, w - padR - tw, y - 3.dp.toPx(), avgPaint
            )
        }
    }
}

/** Forces sensor-landscape while the popup is composed, restoring after. */
@Composable
private fun ForceLandscape() {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
        }
    }
}

// ── Popup: readiness per hour ────────────────────────────────────────────────

/**
 * Full-screen landscape chart of average CCRS for each hour of the day
 * (0–23). The dashed gold line marks the overall average; tapping a bar
 * shows that hour's test count and Green count.
 */
@Composable
fun HourlyReadinessChartPopup(
    hourly: List<HourlyReadiness>,
    overallAvgCcrs: Double,
    onDismiss: () -> Unit
) {
    ForceLandscape()
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    val totalTests = hourly.sumOf { it.testCount }
    val bestHour = hourly.filter { it.testCount > 0 }.maxByOrNull { it.avgCcrs }

    val bars = hourly.map { h ->
        HourBar(
            hour = h.hour,
            hasData = h.testCount > 0,
            frac = if (h.testCount > 0) (h.avgCcrs / 100f).toFloat() else 0f,
            topLabel = if (h.testCount > 0) h.avgCcrs.roundToInt().toString() else "",
            color = if (bestHour != null && h.hour == bestHour.hour) PopupGold else PopupOrange
        )
    }
    val maxValue = 100f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PopupBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "♟ Avg readiness (CCRS) by hour of day",
                    color = PopupTitleColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$totalTests tests" +
                        (bestHour?.let { " · best ${"%02d".format(it.hour)}:00" } ?: ""),
                    color = PopupLabelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = PopupLabelColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            val sel = selectedHour?.let { h -> hourly.firstOrNull { it.hour == h } }
            Text(
                if (sel != null && sel.testCount > 0) {
                    "%02d:00 — %d tests · avg %.1f CCRS · %d green".format(
                        sel.hour, sel.testCount, sel.avgCcrs, sel.greenCount
                    )
                } else {
                    "Tap a bar for that hour's details. Dashed line = overall average (%.1f)."
                        .format(overallAvgCcrs)
                },
                color = PopupDimColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))
            if (totalTests == 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No readiness tests logged yet", color = PopupDimColor, fontSize = 13.sp)
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HourlyBarsCanvas(
                        bars = bars,
                        maxValue = maxValue,
                        avgValue = overallAvgCcrs.toFloat(),
                        avgLabel = "avg %.1f".format(overallAvgCcrs),
                        selectedHour = selectedHour,
                        onTapHour = { h -> selectedHour = if (selectedHour == h) null else h },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ── Popup: win rate per hour ─────────────────────────────────────────────────

/**
 * Full-screen landscape chart of win rate for each hour of the day (0–23),
 * filterable by game subset (All / Post-test / Approved / Unapproved) via
 * the chips at the top. The dashed gold line marks the subset's overall
 * win rate; tapping a bar shows that hour's games and wins.
 */
@Composable
fun HourlyWinRateChartPopup(
    games: List<ReadinessGameRecord>,
    initialFilter: GameFilter,
    onDismiss: () -> Unit
) {
    ForceLandscape()
    var filter by remember { mutableStateOf(initialFilter) }
    var speed by remember { mutableStateOf(GameSpeed.ALL) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    val zone = ZoneId.systemDefault()

    // Speed pre-filter, then subset filter on top — chip counts reflect
    // the other dimension so the user always sees what each toggle holds.
    val speedGames = remember(games, speed) { games.filter { speed.matches(it) } }
    val hourly = remember(games, filter, speed) {
        computeHourlyWinRates(games, filter, zone, speed)
    }
    val totalGames = hourly.sumOf { it.games }
    val totalWins = hourly.sumOf { it.wins }
    val overallWinRate = if (totalGames > 0) totalWins * 100.0 / totalGames else 0.0
    val bestHour = hourly.filter { it.games > 0 }.maxByOrNull { it.winRate }

    val counts = remember(speedGames, filter) {
        GameFilter.entries.associateWith { f -> speedGames.count { f.matches(it) } }
    }
    val speedCounts = remember(games, filter) {
        GameSpeed.entries.associateWith { s ->
            games.count { filter.matches(it) && s.matches(it) }
        }
    }

    val bars = hourly.map { h ->
        HourBar(
            hour = h.hour,
            hasData = h.games > 0,
            frac = if (h.games > 0) h.winRate.toFloat() / 100f else 0f,
            topLabel = if (h.games > 0) "${h.winRate.roundToInt()}%" else "",
            color = gameFilterColor(filter)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PopupBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "♟ Win rate by hour of day",
                    color = PopupTitleColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$totalGames games · $totalWins wins · ${overallWinRate.roundToInt()}%" +
                        (bestHour?.takeIf { it.games >= 2 }?.let {
                            " · best ${"%02d".format(it.hour)}:00"
                        } ?: ""),
                    color = gameFilterColor(filter),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = PopupLabelColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            GameFilterSelector(
                selected = filter,
                onSelect = {
                    filter = it
                    selectedHour = null
                },
                counts = counts
            )
            Spacer(modifier = Modifier.height(4.dp))
            GameSpeedSelector(
                selected = speed,
                onSelect = {
                    speed = it
                    selectedHour = null
                },
                counts = speedCounts
            )

            val sel = selectedHour?.let { h -> hourly.firstOrNull { it.hour == h } }
            Text(
                if (sel != null && sel.games > 0) {
                    "%02d:00 — %d games · %d wins · %.0f%% win".format(
                        sel.hour, sel.games, sel.wins, sel.winRate
                    )
                } else {
                    "Tap a bar for details. Dashed line = overall win rate for the selected subset and speed."
                },
                color = PopupDimColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))
            if (totalGames == 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No games in the “${filter.label}” + “${speed.label}” subset",
                        color = PopupDimColor,
                        fontSize = 13.sp
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HourlyBarsCanvas(
                        bars = bars,
                        maxValue = 100f,
                        avgValue = overallWinRate.toFloat(),
                        avgLabel = "avg ${overallWinRate.roundToInt()}%",
                        selectedHour = selectedHour,
                        onTapHour = { h -> selectedHour = if (selectedHour == h) null else h },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

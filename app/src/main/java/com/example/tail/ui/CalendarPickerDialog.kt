package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.dateString
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ── Colour palette (matches the app's dark theme) ─────────────────────────────

private val BG_DIALOG      = Color(0xFF0D0D0D)
private val BG_HEADER      = Color(0xFF1A1A1A)
private val BG_DAY_BASE    = Color(0xFF111111)   // pure greyscale base for all non-special cells
private val BG_DAY_TODAY   = Color(0xFF1A3A1A)
private val BG_DAY_SELECTED= Color(0xFF0A2A4A)
private val BG_DAY_EMPTY   = Color.Transparent

private val TEXT_HEADER    = Color(0xFFAAAAAA)
private val TEXT_DOW       = Color(0xFF666666)
private val TEXT_DAY       = Color(0xFFCCCCCC)
private val TEXT_DAY_TODAY = Color(0xFF88FF88)
private val TEXT_DAY_SEL   = Color(0xFF4FC3F7)
private val TEXT_DAY_FUTURE= Color(0xFF444444)
private val TEXT_POINTS    = Color(0xFFBBBBBB)    // points label colour (overridden per tier)
private val TEXT_POINTS_ZERO = Color(0xFF333333)
private val BORDER_TODAY   = Color(0xFF44AA44)
private val BORDER_SELECTED= Color(0xFF4FC3F7)

// ── Tiered colour system ──────────────────────────────────────────────────────
//
// Each tier defines a [lo, hi] point range and a target RGB colour.
// Within the tier, intensity = (points - lo) / (hi - lo)  →  0..1
// The cell background is lerped from BG_DAY_BASE toward the tier colour.
// The top tier (white) also flips the day-number text to black.

private data class PointsTier(
    val lo: Int,
    val hi: Int,
    val r: Int, val g: Int, val b: Int   // target colour at full intensity
)

private val POINT_TIERS = listOf(
    PointsTier( 1, 13,  180,  30,  30),   // red
    PointsTier(14, 20,  210, 110,  20),   // orange
    PointsTier(21, 30,   30, 180,  60),   // green
    PointsTier(31, 41,   40, 100, 220),   // blue
    PointsTier(42, 48,  200,  60, 180),   // pink
    PointsTier(49, 55,  210, 190,  30),   // yellow
    PointsTier(56, Int.MAX_VALUE, 230, 230, 230)  // white (text → black)
)

/**
 * Returns the background colour and whether the day text should be dark (black)
 * for a given [points] value using the tiered fade system.
 */
private fun tierColorForPoints(points: Int): Pair<Color, Boolean> {
    if (points <= 0) return Pair(BG_DAY_BASE, false)

    val tier = POINT_TIERS.firstOrNull { points <= it.hi } ?: POINT_TIERS.last()
    val range = (tier.hi - tier.lo).coerceAtLeast(1)
    val intensity = ((points - tier.lo).toFloat() / range).coerceIn(0f, 1f)

    // Base colour components (pure greyscale dark)
    val baseR = 0x11; val baseG = 0x11; val baseB = 0x11

    val r = (baseR + intensity * (tier.r - baseR)).toInt().coerceIn(0, 255)
    val g = (baseG + intensity * (tier.g - baseG)).toInt().coerceIn(0, 255)
    val b = (baseB + intensity * (tier.b - baseB)).toInt().coerceIn(0, 255)

    val useDarkText = (tier.r >= 200 && tier.g >= 200 && tier.b >= 200 && intensity > 0.5f)
    return Pair(Color(r, g, b), useDarkText)
}

private val MONTH_NAMES = listOf(
    "January","February","March","April","May","June",
    "July","August","September","October","November","December"
)

private val DOW_LABELS = listOf("Su","Mo","Tu","We","Th","Fr","Sa")

// ── Public entry point ────────────────────────────────────────────────────────

/**
 * A full-screen-width calendar popup that lets the user jump to any past (or today) date.
 *
 * Features:
 *  - Year/month selector at the top (tap year to open a year picker grid)
 *  - Each day cell shows the total habit points for that day
 *  - Tapping a day closes the dialog and navigates to that day
 *  - Future days are shown dimmed and are not tappable
 *
 * @param initialDate   The date that should be highlighted as "currently selected".
 * @param getDailyTotals  Callback that returns a Map<dateString, totalPoints> for a given year/month.
 * @param onDateSelected  Called with the chosen [LocalDate] when the user taps a day.
 * @param onDismiss       Called when the user dismisses without selecting.
 */
@Composable
fun CalendarPickerDialog(
    initialDate: LocalDate,
    getDailyTotals: (year: Int, month: Int) -> Map<String, Int>,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now()

    // Start on the month of the currently selected date
    var displayYear  by remember { mutableIntStateOf(initialDate.year) }
    var displayMonth by remember { mutableIntStateOf(initialDate.monthValue) }  // 1-based

    // Whether the year-picker overlay is visible
    var showYearPicker by remember { mutableStateOf(false) }

    // Daily totals for the currently displayed month
    var dailyTotals by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // Reload totals whenever the displayed month changes
    LaunchedEffect(displayYear, displayMonth) {
        dailyTotals = getDailyTotals(displayYear, displayMonth)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BG_DIALOG, RoundedCornerShape(16.dp))
                .padding(0.dp)
        ) {
            // ── Header: year/month navigation ─────────────────────────────
            CalendarHeader(
                year        = displayYear,
                month       = displayMonth,
                today       = today,
                onPrevMonth = {
                    if (displayMonth == 1) { displayYear--; displayMonth = 12 }
                    else displayMonth--
                },
                onNextMonth = {
                    // Don't allow navigating past the current month
                    val ym = YearMonth.of(displayYear, displayMonth)
                    val todayYm = YearMonth.now()
                    if (ym.isBefore(todayYm)) {
                        if (displayMonth == 12) { displayYear++; displayMonth = 1 }
                        else displayMonth++
                    }
                },
                onYearClick = { showYearPicker = !showYearPicker }
            )

            if (showYearPicker) {
                // ── Year picker grid ──────────────────────────────────────
                YearPickerGrid(
                    currentYear  = displayYear,
                    maxYear      = today.year,
                    onYearSelected = { yr ->
                        displayYear = yr
                        // If the new year/month combo is in the future, clamp to today's month
                        if (YearMonth.of(yr, displayMonth).isAfter(YearMonth.now())) {
                            displayMonth = today.monthValue
                        }
                        showYearPicker = false
                    }
                )
            } else {
                // ── Day-of-week header row ────────────────────────────────
                DowHeaderRow()

                // ── Calendar day grid ─────────────────────────────────────
                CalendarDayGrid(
                    year         = displayYear,
                    month        = displayMonth,
                    today        = today,
                    selectedDate = initialDate,
                    dailyTotals  = dailyTotals,
                    onDayClick   = { date ->
                        onDateSelected(date)
                    }
                )
            }

            // ── Footer: Cancel ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888899), fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun CalendarHeader(
    year: Int,
    month: Int,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onYearClick: () -> Unit
) {
    val todayYm = YearMonth.now()
    val displayYm = YearMonth.of(year, month)
    val canGoForward = displayYm.isBefore(todayYm)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BG_HEADER, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // ← prev month
        IconButton(onClick = onPrevMonth, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous month",
                tint = TEXT_HEADER,
                modifier = Modifier.size(20.dp)
            )
        }

        // Month name + year (tap year to open year picker)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = MONTH_NAMES[month - 1],
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            // Year chip — tappable
            Box(
                modifier = Modifier
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onYearClick
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = year.toString(),
                    color = Color(0xFF88CCFF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // → next month (disabled when already on current month)
        IconButton(
            onClick = onNextMonth,
            enabled = canGoForward,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next month",
                tint = if (canGoForward) TEXT_HEADER else Color(0xFF333344),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Year picker ───────────────────────────────────────────────────────────────

@Composable
private fun YearPickerGrid(
    currentYear: Int,
    maxYear: Int,
    onYearSelected: (Int) -> Unit
) {
    // Show a range of years: from (maxYear - 15) up to maxYear
    val minYear = maxOf(2000, maxYear - 20)
    val years = (minYear..maxYear).toList().reversed()  // newest first

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Select Year",
            color = TEXT_HEADER,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(years) { yr ->
                val isSelected = yr == currentYear
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                                if (isSelected) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                                RoundedCornerShape(8.dp)
                            )
                        .then(
                            if (isSelected) Modifier.border(1.dp, BORDER_SELECTED, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onYearSelected(yr) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = yr.toString(),
                        color = if (isSelected) Color(0xFFFFFFFF) else TEXT_DAY,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ── Day-of-week header ────────────────────────────────────────────────────────

@Composable
private fun DowHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DOW_LABELS.forEach { label ->
            Text(
                text = label,
                color = TEXT_DOW,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Calendar day grid ─────────────────────────────────────────────────────────

/**
 * Builds the grid of day cells for the given [year]/[month].
 * The grid always starts on Sunday (column 0) and pads with empty cells before
 * the 1st and after the last day of the month.
 */
@Composable
private fun CalendarDayGrid(
    year: Int,
    month: Int,
    today: LocalDate,
    selectedDate: LocalDate,
    dailyTotals: Map<String, Int>,
    onDayClick: (LocalDate) -> Unit
) {
    val firstOfMonth = LocalDate.of(year, month, 1)
    val daysInMonth  = firstOfMonth.lengthOfMonth()
    // dayOfWeek: 1=Mon..7=Sun in Java; we want 0=Sun..6=Sat
    val startOffset  = (firstOfMonth.dayOfWeek.value % 7)  // Sun=0, Mon=1, ..., Sat=6

    // Build a flat list: nulls for leading empty cells, then 1..daysInMonth
    val cells: List<Int?> = buildList {
        repeat(startOffset) { add(null) }
        for (d in 1..daysInMonth) add(d)
        // Pad to a multiple of 7 so the grid is rectangular
        val total = startOffset + daysInMonth
        val remainder = if (total % 7 == 0) 0 else 7 - (total % 7)
        repeat(remainder) { add(null) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        // Render rows of 7
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                week.forEach { day ->
                    if (day == null) {
                        // Empty cell
                        Box(modifier = Modifier.weight(1f).aspectRatio(0.75f))
                    } else {
                        val date = LocalDate.of(year, month, day)
                        val isFuture   = date.isAfter(today)
                        val isToday    = date == today
                        val isSelected = date == selectedDate
                        val ds         = dateString(date)
                        val points     = if (isFuture) null else dailyTotals[ds]

                        DayCell(
                            day        = day,
                            points     = points,
                            isToday    = isToday,
                            isSelected = isSelected,
                            isFuture   = isFuture,
                            onClick    = { if (!isFuture) onDayClick(date) },
                            modifier   = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ── Individual day cell ───────────────────────────────────────────────────────

@Composable
private fun DayCell(
    day: Int,
    points: Int?,          // null = future / no data yet
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Tiered colour system — only applied to normal (non-selected, non-today, non-future) cells
    val (tierBg, useDarkText) = if (!isFuture && !isSelected && !isToday) {
        tierColorForPoints(points ?: 0)
    } else {
        Pair(BG_DAY_BASE, false)
    }

    val bgColor = when {
        isSelected -> BG_DAY_SELECTED
        isToday    -> BG_DAY_TODAY
        isFuture   -> BG_DAY_EMPTY
        else       -> tierBg
    }

    val borderMod = when {
        isSelected -> Modifier.border(1.5.dp, BORDER_SELECTED, RoundedCornerShape(6.dp))
        isToday    -> Modifier.border(1.dp,   BORDER_TODAY,    RoundedCornerShape(6.dp))
        else       -> Modifier
    }

    val dayTextColor = when {
        isFuture    -> TEXT_DAY_FUTURE
        isSelected  -> TEXT_DAY_SEL
        isToday     -> TEXT_DAY_TODAY
        useDarkText -> Color(0xFF111111)
        else        -> TEXT_DAY
    }

    Box(
        modifier = modifier
            .aspectRatio(0.75f)
            .padding(2.dp)
            .background(bgColor, RoundedCornerShape(6.dp))
            .then(borderMod)
            .then(
                if (!isFuture) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(2.dp)
        ) {
            // Day number
            Text(
                text = day.toString(),
                color = dayTextColor,
                fontSize = 13.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            // Points label (only for non-future days)
            if (!isFuture) {
                val pts = points ?: 0
                val ptsColor = when {
                    pts <= 0    -> TEXT_POINTS_ZERO
                    useDarkText -> Color(0xFF333333)
                    else        -> TEXT_POINTS
                }
                Text(
                    text = if (pts > 0) pts.toString() else "·",
                    color = ptsColor,
                    fontSize = 9.sp,
                    fontWeight = if (pts > 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

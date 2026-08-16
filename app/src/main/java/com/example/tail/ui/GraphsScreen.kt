package com.example.tail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Time period options ───────────────────────────────────────────────────────

enum class GraphTimePeriod(val label: String, val days: Int?) {
    WEEK("1W", 7),
    TWO_WEEKS("2W", 14),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR("1Y", 365),
    MAX("Max", null)
}

// ── Graph colors for multiple habits ──────────────────────────────────────────

private val GRAPH_COLORS = listOf(
    Color(0xFF4FC3F7),  // light blue
    Color(0xFFFF8A65),  // orange
    Color(0xFF81C784),  // green
    Color(0xFFBA68C8),  // purple
    Color(0xFFFFD54F),  // yellow
    Color(0xFFE57373),  // red
    Color(0xFF4DD0E1),  // cyan
    Color(0xFFA1887F),  // brown
    Color(0xFFAED581),  // lime
    Color(0xFFF06292),  // pink
)

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("d/M", Locale.ROOT)
private val YEAR_DATE_FMT = DateTimeFormatter.ofPattern("M/yy", Locale.ROOT)
private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT)

// Custom date formatters that guarantee numeric output
private fun formatShortDate(date: LocalDate): String {
    return "${date.dayOfMonth}/${date.monthValue}"
}

private fun formatYearDate(date: LocalDate): String {
    return "${date.monthValue}/${date.year.toString().takeLast(2)}"
}

private fun formatFullDate(date: LocalDate): String {
    return "${date.dayOfMonth}/${date.monthValue}/${date.year}"
}

// ── Main Graphs Content ───────────────────────────────────────────────────────

/**
 * The graphs panel shown below the habit grid (portrait) or fullscreen (landscape).
 *
 * Portrait: shows time period controls + chart + stats summary + legend
 * Landscape: shows time period controls + chart only (no stats, no habit chips)
 *
 * Habit selection is done by tapping the habit icons in the grid above — no extra chips here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GraphsPanel(
    viewModel: HabitViewModel,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    garminHabitLinks: Map<String, String> = emptyMap()
) {
    val graphSelectedHabits by viewModel.graphSelectedHabits.collectAsState()
    val selectedPeriod by viewModel.graphTimePeriod.collectAsState()
    val zoomStartDate by viewModel.graphZoomStartDate.collectAsState()
    val zoomEndDate by viewModel.graphZoomEndDate.collectAsState()
    val habits by viewModel.habits.collectAsState()
    // Collect settings so the graph recomputes when metric selection changes
    val settings by viewModel.settings.collectAsState()
    val metricSelection = settings.graphMetricSelection
    // Per-metric "interpolate zeros" — recomputes series when toggled
    val interpolateZeroMetrics = settings.graphInterpolateZeroMetrics
    // Text-entry cache — recomputes series once entries finish loading (movie runtimes)
    val textEntriesCache by viewModel.textEntriesCache.collectAsState()

    var selectedDataPoint by remember { mutableStateOf<SelectedPoint?>(null) }
    var textEntriesForPoint by remember { mutableStateOf<List<String>>(emptyList()) }
    var datedEntriesForPoint by remember { mutableStateOf<List<String>>(emptyList()) }
    var imdbRatingsForPoint by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var commitMessagesForPoint by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Text filter state
    var showFilterDialog by remember { mutableStateOf(false) }
    var textFilter by remember { mutableStateOf("") }
    
    // Check if any selected habit is a text-input habit
    val hasTextInputHabit = graphSelectedHabits.any { viewModel.isTextInputHabit(it) }

    // When selection or period changes, clear the selected data point
    LaunchedEffect(graphSelectedHabits, selectedPeriod, zoomStartDate, zoomEndDate) {
        selectedDataPoint = null
        textEntriesForPoint = emptyList()
        datedEntriesForPoint = emptyList()
        imdbRatingsForPoint = emptyMap()
        commitMessagesForPoint = emptyList()
        
        // Load text entries for text-input habits into the cache
        graphSelectedHabits.forEach { habitName ->
            if (viewModel.isTextInputHabit(habitName)) {
                viewModel.loadTextEntriesForGraph(habitName)
            }
        }
    }

    // When a data point is selected, load text entries and/or dated entries if applicable
    LaunchedEffect(selectedDataPoint) {
        val point = selectedDataPoint
        if (point != null) {
            if (viewModel.isTextInputHabit(point.habitName)) {
                viewModel.loadTextEntriesForDate(point.habitName, point.date) { entries ->
                    textEntriesForPoint = entries
                }
                // Load IMDb ratings for movie-bridge habits
                if (viewModel.hasImdbRatings(point.habitName)) {
                    imdbRatingsForPoint = viewModel.getImdbRatingsForDate(point.habitName, point.date)
                } else {
                    imdbRatingsForPoint = emptyMap()
                }
            } else {
                textEntriesForPoint = emptyList()
                imdbRatingsForPoint = emptyMap()
            }
            if (viewModel.isDatedEntryHabit(point.habitName)) {
                viewModel.loadDatedEntriesForDate(point.habitName, point.date) { chunks ->
                    datedEntriesForPoint = chunks
                }
            } else {
                datedEntriesForPoint = emptyList()
            }
            // Load the actual git commit messages when the Commits metric is shown
            if (point.metric == com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS &&
                viewModel.isGithubHabit(point.habitName)
            ) {
                commitMessagesForPoint = viewModel.getCommitMessagesForDate(point.habitName, point.date)
            } else {
                commitMessagesForPoint = emptyList()
            }
        } else {
            textEntriesForPoint = emptyList()
            datedEntriesForPoint = emptyList()
            imdbRatingsForPoint = emptyMap()
            commitMessagesForPoint = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1A0A))
    ) {
        // ── Time period selector — shown in both portrait and landscape ────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GraphTimePeriod.entries.forEach { period ->
                val isActive = period == selectedPeriod
                Text(
                    text = period.label,
                    color = if (isActive) Color(0xFF000000) else Color(0xFF88AA88),
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            if (isActive) Color(0xFF66DD66) else Color(0xFF1A2E1A),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.setGraphTimePeriod(period) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            
            // Filter button - only shown for text-input habits
            if (hasTextInputHabit) {
                Text(
                    text = if (textFilter.isEmpty()) "Filter" else "Filter*",
                    color = if (textFilter.isNotEmpty()) Color(0xFF000000) else Color(0xFF88AA88),
                    fontSize = 11.sp,
                    fontWeight = if (textFilter.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            if (textFilter.isNotEmpty()) Color(0xFF66DD66) else Color(0xFF1A2E1A),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showFilterDialog = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // ── Multi-select metric toggle — shown when exactly one habit is selected ────
        // Multiple metrics can be active simultaneously; each renders as a separate line.
        // Meal habits show additional options (Calories, Protein, Carbs, Fat).
        if (graphSelectedHabits.size == 1) {
            val selectedHabit = graphSelectedHabits.first()
            val availableMetrics = viewModel.getAvailableMetrics(selectedHabit)
            val selectedMetrics = viewModel.getSelectedMetrics(selectedHabit)
            // Long-pressed metric awaiting the "Interp 0s" popup
            var interpMenuMetric by remember { mutableStateOf<String?>(null) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                availableMetrics.forEachIndexed { index, option ->
                    val isActive = option.key in selectedMetrics
                    // Match the line color: use the metric's position in availableMetrics
                    val metricColor = GRAPH_COLORS[index % GRAPH_COLORS.size]
                    val interpActive = viewModel.isGraphInterpolateZeroEnabled(selectedHabit, option.key)
                    Box(
                        modifier = Modifier
                            .background(
                                if (isActive) metricColor else Color(0xFF1A2E1A),
                                RoundedCornerShape(8.dp)
                            )
                            .combinedClickable(
                                onClick = { viewModel.toggleGraphMetric(selectedHabit, option.key) },
                                onLongClick = { interpMenuMetric = option.key },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = option.label,
                            color = if (isActive) Color(0xFF000000) else Color(0xFF88AA88),
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                        // Struck-through 0 badge: zeros are interpolated for this metric
                        if (interpActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = (-6).dp)
                                    .size(13.dp)
                                    .background(Color(0xFF0D1A0D), CircleShape)
                                    .border(1.dp, Color(0xFF66DD66), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "0",
                                    textDecoration = TextDecoration.LineThrough,
                                    color = Color(0xFF66DD66),
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Long-press popup: per-metric "Interp 0s" toggle
            interpMenuMetric?.let { metricKey ->
                Popup(
                    alignment = Alignment.TopCenter,
                    properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
                    onDismissRequest = { interpMenuMetric = null }
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF0D1A0D), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF3A5A3A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = viewModel.isGraphInterpolateZeroEnabled(selectedHabit, metricKey),
                            onCheckedChange = { enabled ->
                                viewModel.setGraphInterpolateZero(selectedHabit, metricKey, enabled)
                            }
                        )
                        Text(
                            text = "Interp 0s",
                            color = Color(0xFFCCEECC),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        // ── Chart area ────────────────────────────────────────────────────
        if (graphSelectedHabits.isNotEmpty()) {
            val today = LocalDate.now()
            val earliestDate = viewModel.getEarliestDate(graphSelectedHabits)

            // Use zoom range if set, otherwise use period-based range
            val fullStartDate = zoomStartDate ?: when {
                selectedPeriod?.days != null -> today.minusDays(selectedPeriod!!.days!!.toLong() - 1)
                earliestDate != null -> earliestDate
                else -> today.minusDays(29)
            }
            val fullEndDate = zoomEndDate ?: today

            // Collect data for all selected habits × selected metrics.
            // Each (habit, metric) pair becomes a separate line on the chart.
            val allSeriesData = remember(graphSelectedHabits, selectedPeriod, zoomStartDate, zoomEndDate, textFilter, metricSelection, interpolateZeroMetrics, textEntriesCache) {
                val isSingleHabit = graphSelectedHabits.size == 1
                var sequentialColorIdx = 0
                graphSelectedHabits.toList().flatMap { habitName ->
                    val data = viewModel.getGraphData(habitName, fullStartDate, fullEndDate, textFilter)
                    val metrics = viewModel.getSelectedMetrics(habitName)
                    val availableMetrics = viewModel.getAvailableMetrics(habitName)
                    // Preserve a stable ordering: points, value1, value2, then meal metrics
                    val orderedMetrics = availableMetrics
                        .map { it.key }
                        .filter { it in metrics }
                    val metricsToShow = orderedMetrics.ifEmpty { metrics.toList() }
                    metricsToShow.map { metricKey ->
                        // For single-habit mode, use the metric's position in availableMetrics
                        // so button colors match line colors deterministically.
                        // For multi-habit mode, use a sequential counter.
                        val color = if (isSingleHabit) {
                            val idx = availableMetrics.indexOfFirst { it.key == metricKey }
                            GRAPH_COLORS[(if (idx >= 0) idx else 0) % GRAPH_COLORS.size]
                        } else {
                            GRAPH_COLORS[sequentialColorIdx++ % GRAPH_COLORS.size]
                        }
                        GraphSeries(
                            habitName = habitName,
                            data = data,
                            color = color,
                            isTextInput = viewModel.isTextInputHabit(habitName),
                            metric = metricKey,
                            metricLabel = com.example.tail.data.displayLabelForValue(
                                habitName, metricKey, settings.valueDisplayLabels
                            )
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) Modifier.weight(1f)
                        else Modifier.height(220.dp)
                    )
                    .padding(horizontal = 4.dp)
            ) {
                HabitLineChart(
                    seriesData = allSeriesData,
                    fullStartDate = fullStartDate,
                    fullEndDate = fullEndDate,
                    onPointSelected = { point -> selectedDataPoint = point },
                    selectedPoint = selectedDataPoint,
                    onZoom = { newStart, newEnd ->
                        viewModel.setGraphZoomRange(newStart, newEnd)
                    },
                    onZoomReset = { viewModel.clearGraphZoom() },
                    garminHabitLinks = garminHabitLinks,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Scrollable area below the chart (tooltip / stats / legend) ─
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Selected point info tooltip (with X to close) ─────────
                selectedDataPoint?.let { point ->
                    val hasContent = textEntriesForPoint.isNotEmpty() || datedEntriesForPoint.isNotEmpty() ||
                        commitMessagesForPoint.isNotEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A2E1A), RoundedCornerShape(8.dp))
                    ) {
                        // Header row: date + value + X button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatFullDate(point.date),
                                color = Color(0xFFCCEECC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        viewModel.navigateToDate(point.date)
                                    }
                            )
                            val garminType = garminHabitLinks[point.habitName]?.let { com.example.tail.data.GarminType.fromKey(it) }
                            val valueText = if (garminType == com.example.tail.data.GarminType.FITNESS_AGE ||
                                                garminType == com.example.tail.data.GarminType.FITNESS_AGE_DISTANCE) {
                                String.format("%.2f", point.value / 100.0)
                            } else if (point.metric == com.example.tail.data.GRAPH_METRIC_IMDB && point.value > 0) {
                                String.format("%.1f", point.value / 10.0)
                            } else if (point.metric == com.example.tail.data.GRAPH_METRIC_RUNTIME && point.value > 0) {
                                formatRuntimeMinutes(point.value)
                            } else {
                                point.value.toString()
                            }
                            val displayLabel = if (point.metricLabel.isNotEmpty() &&
                                point.metricLabel != "Points" && point.metricLabel != "Value 1") {
                                "${point.habitName} (${point.metricLabel})"
                            } else {
                                point.habitName
                            }
                            Text(
                                text = "$displayLabel: $valueText",
                                color = point.color,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            // X button to dismiss tooltip
                            IconButton(
                                onClick = { selectedDataPoint = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFF889988),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Extra content (raw value / text entries / dated entries)
                        if (hasContent || point.rawValue != point.value) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                            ) {
                                if (point.rawValue != point.value) {
                                    Text(
                                        text = "Raw: ${point.rawValue}",
                                        color = Color(0xFF889988),
                                        fontSize = 10.sp
                                    )
                                }
                                if (textEntriesForPoint.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    HorizontalDivider(color = Color(0xFF334433), thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Text entries:",
                                        color = Color(0xFF88CC88),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    textEntriesForPoint.forEach { entry ->
                                        val rating = imdbRatingsForPoint[entry]
                                        val displayText = if (rating != null) {
                                            "\u2022 $entry  \u2B50 $rating"
                                        } else {
                                            "\u2022 $entry"
                                        }
                                        Text(
                                            text = displayText,
                                            color = Color(0xFFCCEECC),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                        )
                                    }
                                }
                                if (commitMessagesForPoint.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    HorizontalDivider(color = Color(0xFF334433), thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Commits (${commitMessagesForPoint.size}):",
                                        color = Color(0xFF66CCFF),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    commitMessagesForPoint.forEach { msg ->
                                        Text(
                                            text = "\u2022 $msg",
                                            color = Color(0xFFCCEECC),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                        )
                                    }
                                }
                                if (datedEntriesForPoint.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    HorizontalDivider(color = Color(0xFF334433), thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Entries (${datedEntriesForPoint.size}):",
                                        color = Color(0xFFFFCC44),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    datedEntriesForPoint.forEachIndexed { idx, chunk ->
                                        if (idx > 0) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            HorizontalDivider(
                                                color = Color(0xFF2A2A1A),
                                                thickness = 0.5.dp,
                                                modifier = Modifier.padding(start = 4.dp)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        Text(
                                            text = chunk,
                                            color = Color(0xFFEEDDAA),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // ── Meal daily totals ───────────────────────────────
                        val mealTotals = viewModel.getMealDayTotals(point.habitName, point.date)
                        if (mealTotals != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = Color(0xFF334433), thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Day totals (${mealTotals.mealCount} meal${if (mealTotals.mealCount != 1) "s" else ""}):",
                                color = Color(0xFFFFCC44),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("🔥 ${mealTotals.calories} kcal", color = Color(0xFFEEDDAA), fontSize = 10.sp)
                                Text("P ${mealTotals.proteinGrams.toInt()}g", color = Color(0xFFCCEECC), fontSize = 10.sp)
                                Text("C ${mealTotals.carbsGrams.toInt()}g", color = Color(0xFFCCEECC), fontSize = 10.sp)
                                Text("F ${mealTotals.fatGrams.toInt()}g", color = Color(0xFFCCEECC), fontSize = 10.sp)
                            }
                        }
                    }
                }

                // ── Stats summary — portrait only ─────────────────────────
                if (!isLandscape && selectedDataPoint == null) {
                    StatsSummary(
                        seriesData = allSeriesData,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // ── Legend — shown when more than one series is on the chart ─
                if (allSeriesData.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        allSeriesData.forEach { series ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (isLandscape) 6.dp else 8.dp)
                                        .background(series.color, CircleShape)
                                )
                                Text(
                                    text = if (series.metricLabel != "Points" && series.metricLabel != "Value 1")
                                        "${series.habitName} (${series.metricLabel})"
                                    else series.habitName,
                                    color = series.color,
                                    fontSize = if (isLandscape) 9.sp else 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // ── Info panels for each selected habit ──────────────────
                if (!isLandscape) {
                    val selectedHabitObjects = graphSelectedHabits.mapNotNull { name ->
                        habits.find { it.name == name }
                    }
                    selectedHabitObjects.forEach { habit ->
                        HabitInfoPanel(
                            habit = habit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            garminHabitLinks = garminHabitLinks
                        )
                    }
                }
            }
        } else {
            // No habits selected — show prompt
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) Modifier.weight(1f)
                        else Modifier.height(120.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📊", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isLandscape)
                            "Tap habit icons (portrait) to add them to the graph"
                        else
                            "Tap habit icons above to add them to the graph",
                        color = Color(0xFF668866),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
    
    // Text filter dialog
    if (showFilterDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text(
                    text = "Filter Text Entries",
                    color = Color(0xFFCCEECC),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Only show habit points where the text contains:",
                        color = Color(0xFF88AA88),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = textFilter,
                        onValueChange = { textFilter = it },
                        placeholder = { Text("Enter filter text...", color = Color(0xFF668866)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFCCEECC),
                            unfocusedTextColor = Color(0xFFCCEECC),
                            focusedBorderColor = Color(0xFF66DD66),
                            unfocusedBorderColor = Color(0xFF446644),
                            cursorColor = Color(0xFF66DD66),
                            focusedPlaceholderColor = Color(0xFF668866),
                            unfocusedPlaceholderColor = Color(0xFF668866)
                        )
                    )
                    if (textFilter.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Leave empty to clear the filter",
                            color = Color(0xFF668866),
                            fontSize = 10.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showFilterDialog = false }
                ) {
                    Text("Apply", color = Color(0xFF66DD66))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        textFilter = ""
                        showFilterDialog = false
                    }
                ) {
                    Text("Clear", color = Color(0xFF88AA88))
                }
            },
            containerColor = Color(0xFF0D1A0D)
        )
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Returns the display value for a data point based on the graph metric key.
 * Supports both legacy modes (points/value1/value2) and meal metrics
 * (calories/protein/carbs/fat).
 */
private fun displayValueForMetric(
    dp: HabitViewModel.GraphDataPoint,
    metric: String
): Int = when (metric) {
    com.example.tail.data.GRAPH_METRIC_VALUE1 -> dp.garminValue ?: dp.rawValue
    // No rawValue fallback: days without a secondary entry must read as 0,
    // otherwise sparse secondary data (e.g. timer minutes) would display the
    // primary value's history.
    com.example.tail.data.GRAPH_METRIC_VALUE2 -> dp.secondaryValue ?: 0
    com.example.tail.data.GRAPH_METRIC_VALUE3 -> dp.tertiaryValue ?: 0
    com.example.tail.data.GRAPH_METRIC_IMDB -> dp.secondaryValue ?: 0
    com.example.tail.data.GRAPH_METRIC_RUNTIME -> dp.movieRuntimeMinutes ?: 0
    com.example.tail.data.GRAPH_METRIC_CALORIES -> dp.mealCalories ?: 0
    com.example.tail.data.GRAPH_METRIC_PROTEIN -> dp.mealProtein ?: 0
    com.example.tail.data.GRAPH_METRIC_CARBS -> dp.mealCarbs ?: 0
    com.example.tail.data.GRAPH_METRIC_FAT -> dp.mealFat ?: 0
    com.example.tail.data.GRAPH_METRIC_GITHUB_LINES -> dp.githubLinesChanged ?: 0
    com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS -> dp.githubCommits ?: 0
    com.example.tail.data.GRAPH_METRIC_GITHUB_ADDITIONS -> dp.githubAdditions ?: 0
    com.example.tail.data.GRAPH_METRIC_GITHUB_DELETIONS -> dp.githubDeletions ?: 0
    else -> dp.pointsValue
}

/**
 * Formats a display value for the tooltip, with special handling for IMDb
 * ratings (stored as rating x 10, displayed as a decimal like "8.8").
 */
fun formatTooltipValue(value: Int, metric: String): String {
    return when {
        metric == com.example.tail.data.GRAPH_METRIC_IMDB && value > 0 ->
            String.format("%.1f", value / 10.0)
        metric == com.example.tail.data.GRAPH_METRIC_RUNTIME && value > 0 ->
            formatRuntimeMinutes(value)
        else -> value.toString()
    }
}

/** Formats a minute total compactly, e.g. 142 → "2h 22m", 45 → "45m". */
private fun formatRuntimeMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** Returns the human-readable label for a metric key. */
fun metricLabel(metric: String): String = when (metric) {
    com.example.tail.data.GRAPH_METRIC_VALUE1 -> "Value 1"
    com.example.tail.data.GRAPH_METRIC_VALUE2 -> "Value 2"
    com.example.tail.data.GRAPH_METRIC_VALUE3 -> "Value 3"
    com.example.tail.data.GRAPH_METRIC_IMDB -> "IMDb Avg"
    com.example.tail.data.GRAPH_METRIC_RUNTIME -> "Runtime"
    com.example.tail.data.GRAPH_METRIC_CALORIES -> "Calories"
    com.example.tail.data.GRAPH_METRIC_PROTEIN -> "Protein"
    com.example.tail.data.GRAPH_METRIC_CARBS -> "Carbs"
    com.example.tail.data.GRAPH_METRIC_FAT -> "Fat"
    com.example.tail.data.GRAPH_METRIC_GITHUB_LINES -> "Lines Changed"
    com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS -> "Commits"
    com.example.tail.data.GRAPH_METRIC_GITHUB_ADDITIONS -> "Additions"
    com.example.tail.data.GRAPH_METRIC_GITHUB_DELETIONS -> "Deletions"
    else -> "Points"
}

data class GraphSeries(
    val habitName: String,
    val data: List<HabitViewModel.GraphDataPoint>,
    val color: Color,
    val isTextInput: Boolean = false,
    /** Metric key (see [com.example.tail.data.GRAPH_METRIC_POINTS] etc.). */
    val metric: String = com.example.tail.data.GRAPH_METRIC_POINTS,
    /** Human-readable metric label for legend / tooltip. */
    val metricLabel: String = "Points"
)

data class SelectedPoint(
    val habitName: String,
    val date: LocalDate,
    val value: Int,
    val rawValue: Int,
    val color: Color,
    val metricLabel: String = "",
    val metric: String = com.example.tail.data.GRAPH_METRIC_POINTS
)

/**
 * Per-series Y-axis scale info used when series have wildly different magnitudes.
 * Each series is normalised independently so small-value lines remain visible.
 */
data class SeriesYScale(
    val effectiveMin: Int,
    val effectiveMax: Int,
    val range: Int,
    val ticks: List<Int>,
    val onRight: Boolean   // true → draw tick labels on the right side of the chart
)

// ── Custom Canvas Line Chart ─────────────────────────────────────────────────

@Composable
private fun HabitLineChart(
    seriesData: List<GraphSeries>,
    fullStartDate: LocalDate,
    fullEndDate: LocalDate,
    onPointSelected: (SelectedPoint?) -> Unit,
    selectedPoint: SelectedPoint?,
    onZoom: (LocalDate, LocalDate) -> Unit,
    onZoomReset: () -> Unit,
    garminHabitLinks: Map<String, String> = emptyMap(),  // habitName -> GarminType key
    modifier: Modifier = Modifier
) {
    val fullTotalDays = ChronoUnit.DAYS.between(fullStartDate, fullEndDate).toInt() + 1

    // Pinch-to-zoom state — relative to the full period range (stable, never resets on zoom)
    // zoomScale=1 means show the full range; zoomScale=2 means show half the range, etc.
    // zoomCenter is 0..1 fraction of fullTotalDays indicating the center of the visible window
    var zoomScale by remember(fullStartDate, fullEndDate) { mutableFloatStateOf(1f) }
    var zoomCenter by remember(fullStartDate, fullEndDate) { mutableFloatStateOf(1f) }  // default: right edge (today)

    // Drag state for smooth scrolling.
    // dragOffsetPx is a continuous horizontal pixel offset applied to the whole
    // chart while the finger is moving, so the graph pans in real time. Whenever
    // the accumulated offset exceeds one day's pixel width we commit a whole-day
    // shift to the visible window and subtract that width back out, keeping the
    // motion seamless.
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    // Derive the visible date range from zoom state
    val visStartDate: LocalDate
    val visEndDate: LocalDate
    val visTotalDays: Int
    if (zoomScale <= 1.01f) {
        visStartDate = fullStartDate
        visEndDate = fullEndDate
        visTotalDays = fullTotalDays
    } else {
        val visibleDays = (fullTotalDays / zoomScale).toInt().coerceAtLeast(2)
        val centerDayIdx = (zoomCenter * (fullTotalDays - 1)).toInt().coerceIn(0, fullTotalDays - 1)
        val halfVisible = visibleDays / 2
        val visStartIdx = (centerDayIdx - halfVisible).coerceIn(0, (fullTotalDays - visibleDays).coerceAtLeast(0))
        val visEndIdx = (visStartIdx + visibleDays - 1).coerceIn(0, fullTotalDays - 1)
        visStartDate = fullStartDate.plusDays(visStartIdx.toLong())
        visEndDate = fullStartDate.plusDays(visEndIdx.toLong())
        visTotalDays = ChronoUnit.DAYS.between(visStartDate, visEndDate).toInt() + 1
    }

    // Fresh snapshots of the visible window, read by the stable (Unit-keyed) drag
    // gesture so it never needs to be restarted when the window changes.
    val currentVisStart by rememberUpdatedState(visStartDate)
    val currentVisEnd by rememberUpdatedState(visEndDate)
    val currentVisTotalDays by rememberUpdatedState(visTotalDays)

    // Find global min and max for Y axis (over the visible range)
    val globalMax = seriesData.maxOfOrNull { series ->
        series.data.filter { it.date >= visStartDate && it.date <= visEndDate }
            .maxOfOrNull { dp -> displayValueForMetric(dp, series.metric) } ?: 0
    } ?: 1
    val globalMin = seriesData.minOfOrNull { series ->
        series.data.filter { it.date >= visStartDate && it.date <= visEndDate }
            .minOfOrNull { dp -> displayValueForMetric(dp, series.metric) } ?: 0
    } ?: 0

    val yMin = globalMin
    val yMax = if (globalMax == 0) 1 else globalMax
    val yTicks = calculateYTicks(yMin, yMax)
    val effectiveYMin = yTicks.firstOrNull() ?: yMin
    val effectiveYMax = yTicks.lastOrNull() ?: yMax
    val yRange = effectiveYMax - effectiveYMin

    // ── Multi-scale detection ────────────────────────────────────────────
    // When series have wildly different magnitudes (e.g. points ~5 vs calories
    // ~2000) the shared axis squishes small-value lines flat.  Detect this and
    // give each series its own independent scale, with coloured Y-axis labels.
    val seriesMaxValues = seriesData.map { series ->
        series.data.filter { it.date >= visStartDate && it.date <= visEndDate }
            .maxOfOrNull { displayValueForMetric(it, series.metric) } ?: 0
    }
    val nonZeroMaxes = seriesMaxValues.filter { it > 0 }
    val useMultiScale = seriesData.size > 1 && nonZeroMaxes.size >= 2 &&
        (nonZeroMaxes.maxOrNull()!!.toFloat() / nonZeroMaxes.minOrNull()!!.toFloat()) > 5f

    val seriesYScales: Map<GraphSeries, SeriesYScale> = if (useMultiScale) {
        seriesData.mapIndexed { idx, series ->
            val sMax = seriesMaxValues[idx]
            val sMin = series.data.filter { it.date >= visStartDate && it.date <= visEndDate }
                .minOfOrNull { displayValueForMetric(it, series.metric) } ?: 0
            val ticks = calculateYTicks(sMin, sMax)
            val eMin = ticks.firstOrNull() ?: sMin
            val eMax = ticks.lastOrNull() ?: if (sMax == 0) 1 else sMax
            series to SeriesYScale(
                effectiveMin = eMin,
                effectiveMax = eMax,
                range = (eMax - eMin).coerceAtLeast(1),
                ticks = ticks,
                onRight = idx % 2 == 1   // alternate left / right
            )
        }.toMap()
    } else {
        emptyMap()
    }
    val hasRightAxis = useMultiScale && seriesYScales.values.any { it.onRight }

    /** Map a display value to a Y pixel for the given series (multi-scale aware). */
    fun scaleYForSeries(displayValue: Int, series: GraphSeries, cBottom: Float, cHeight: Float): Float {
        val sc = seriesYScales[series]
        return if (sc != null) {
            cBottom - ((displayValue - sc.effectiveMin).toFloat() / sc.range) * cHeight
        } else {
            cBottom - ((displayValue - effectiveYMin.toFloat()) / yRange.coerceAtLeast(1)) * cHeight
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    // Handle pinch-to-zoom
                    val newScale = (zoomScale * zoomChange).coerceIn(1f, fullTotalDays.toFloat().coerceAtLeast(2f))
                    zoomScale = newScale
                    zoomCenter = 1f  // Keep at right edge

                    if (newScale <= 1.01f) {
                        onZoomReset()
                    } else {
                        val visibleDays = (fullTotalDays / newScale).toInt().coerceAtLeast(2)
                        val centerDayIdx = (zoomCenter * (fullTotalDays - 1)).toInt().coerceIn(0, fullTotalDays - 1)
                        val halfVisible = visibleDays / 2
                        val visStartIdx = (centerDayIdx - halfVisible).coerceIn(0, (fullTotalDays - visibleDays).coerceAtLeast(0))
                        val visEndIdx = (visStartIdx + visibleDays - 1).coerceIn(0, fullTotalDays - 1)
                        val newStart = fullStartDate.plusDays(visStartIdx.toLong())
                        val newEnd = fullStartDate.plusDays(visEndIdx.toLong())
                        onZoom(newStart, newEnd)
                    }
                }
            }
            // Horizontal pan / scroll-through-time gesture.
            //
            // Key on Unit so this gesture coroutine is NEVER cancelled/restarted
            // mid-drag. (If we keyed it on visStartDate/visEndDate, committing an
            // onZoom during the drag would change those keys, recompose, and
            // cancel the in-progress drag — which is exactly why it used to move
            // only one data point per swipe.)
            //
            // During the drag we ONLY accumulate a local pixel offset, so the
            // whole chart translates with the finger in real time for the entire
            // duration of the swipe. We commit the window shift to onZoom exactly
            // once, at drag end. currentVis* are rememberUpdatedState wrappers so
            // we always read the freshest visible window without restarting.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val visStart = currentVisStart
                        val visEnd = currentVisEnd
                        val visDays = currentVisTotalDays

                        val chartLeftPx = 40.dp.toPx()
                        val chartRightPx = size.width - 12.dp.toPx()
                        val chartWidthPx = (chartRightPx - chartLeftPx).coerceAtLeast(1f)
                        val dayWidthPx = chartWidthPx / (visDays - 1).coerceAtLeast(1)

                        // Convert the total accumulated pixel offset into a whole
                        // number of days to shift the window by. Positive offset =
                        // finger moved right = go back in time (earlier dates).
                        val daysToShift = Math.round(dragOffsetPx / dayWidthPx).toLong()
                        if (daysToShift != 0L) {
                            val windowDays = ChronoUnit.DAYS.between(visStart, visEnd).toInt()
                            var newStart = visStart.minusDays(daysToShift)
                            var newEnd = newStart.plusDays(windowDays.toLong())

                            // Clamp so we never scroll past today into the future.
                            val today = LocalDate.now()
                            if (newEnd.isAfter(today)) {
                                newEnd = today
                                newStart = today.minusDays(windowDays.toLong())
                            }
                            onZoom(newStart, newEnd)
                        }
                        // Reset the live offset; the recomposed window now reflects
                        // the shift, so the chart lands exactly where the finger left it.
                        dragOffsetPx = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    // Pan the whole chart with the finger in real time. No onZoom
                    // here — the render layer applies dragOffsetPx via translate().
                    dragOffsetPx += dragAmount.x
                }
            }
            .pointerInput(seriesData, visStartDate, visEndDate) {
                detectTapGestures { offset ->
                    val chartLeft = 40.dp.toPx()
                    val chartRight = if (hasRightAxis) size.width - 40.dp.toPx() else size.width - 12.dp.toPx()
                    val chartTop = 12.dp.toPx()
                    val chartBottom = size.height - 28.dp.toPx()
                    val chartWidth = chartRight - chartLeft
                    val chartHeight = chartBottom - chartTop

                    if (visTotalDays <= 0 || chartWidth <= 0) return@detectTapGestures

                    val tapX = offset.x
                    val tapY = offset.y

                    var closestPoint: SelectedPoint? = null
                    var closestDist = Float.MAX_VALUE

                    for (series in seriesData) {
                        for (dp in series.data) {
                            if (dp.date < visStartDate || dp.date > visEndDate) continue
                            val dayIdx = ChronoUnit.DAYS.between(visStartDate, dp.date).toInt()
                            val x = chartLeft + (dayIdx.toFloat() / (visTotalDays - 1).coerceAtLeast(1)) * chartWidth
                            val displayValue = displayValueForMetric(dp, series.metric)
                            val y = scaleYForSeries(displayValue, series, chartBottom, chartHeight)

                            val dist = kotlin.math.sqrt(
                                (tapX - x) * (tapX - x) + (tapY - y) * (tapY - y)
                            )
                            if (dist < closestDist && dist < 60.dp.toPx()) {
                                closestDist = dist
                                closestPoint = SelectedPoint(
                                    habitName = series.habitName,
                                    date = dp.date,
                                    value = displayValue,
                                    rawValue = dp.garminValue ?: dp.rawValue,
                                    color = series.color,
                                    metricLabel = series.metricLabel,
                                    metric = series.metric
                                )
                            }
                        }
                    }
                    onPointSelected(closestPoint)
                }
            }
    ) {
        val chartLeft = 40.dp.toPx()
        val chartRight = if (hasRightAxis) size.width - 40.dp.toPx() else size.width - 12.dp.toPx()
        val chartTop = 12.dp.toPx()
        val chartBottom = size.height - 28.dp.toPx()
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        // Pixel width of a single day, used to apply the live drag pan offset.
        val dayWidthPx = chartWidth / (visTotalDays - 1).coerceAtLeast(1)

        if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

        // ── Y axis labels and grid lines ──────────────────────────────────
        val textPaint = android.graphics.Paint().apply {
            color = 0xFF668866.toInt()
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        // Check if any series is a Garmin fitness age metric that needs decimal formatting
        val needsDecimalFormatting = seriesData.any { series ->
            val garminType = garminHabitLinks[series.habitName]?.let { com.example.tail.data.GarminType.fromKey(it) }
            garminType == com.example.tail.data.GarminType.FITNESS_AGE ||
            garminType == com.example.tail.data.GarminType.FITNESS_AGE_DISTANCE
        }

        if (useMultiScale) {
            // ── Per-series coloured Y-axis labels ──────────────────────────
            seriesData.forEach { series ->
                val sc = seriesYScales[series] ?: return@forEach
                val seriesColor = series.color
                val colorInt = android.graphics.Color.argb(
                    255,
                    (seriesColor.red * 255).toInt().coerceIn(0, 255),
                    (seriesColor.green * 255).toInt().coerceIn(0, 255),
                    (seriesColor.blue * 255).toInt().coerceIn(0, 255)
                )
                val axisPaint = android.graphics.Paint().apply {
                    this.color = colorInt
                    textSize = 9.dp.toPx()
                    isAntiAlias = true
                    textAlign = if (sc.onRight) android.graphics.Paint.Align.LEFT
                                else android.graphics.Paint.Align.RIGHT
                    isFakeBoldText = true
                }
                // Limit to 4 ticks per series to avoid clutter
                val displayTicks = if (sc.ticks.size > 4) {
                    val step = (sc.ticks.size + 3) / 4
                    sc.ticks.filterIndexed { idx, _ -> idx % step == 0 }
                } else {
                    sc.ticks
                }
                for (tick in displayTicks) {
                    val y = chartBottom - ((tick - sc.effectiveMin).toFloat() / sc.range) * chartHeight
                    drawLine(
                        color = seriesColor.copy(alpha = 0.06f),
                        start = Offset(chartLeft, y),
                        end = Offset(chartRight, y),
                        strokeWidth = 0.5.dp.toPx()
                    )
                    val tickLabel = if (needsDecimalFormatting) {
                        String.format("%.2f", tick / 100.0)
                    } else {
                        tick.toString()
                    }
                    val labelX = if (sc.onRight) chartRight + 4.dp.toPx()
                                 else chartLeft - 4.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(
                        tickLabel, labelX, y + 3.dp.toPx(), axisPaint
                    )
                }
            }
        } else {
            for (tick in yTicks) {
                val y = chartBottom - ((tick - effectiveYMin).toFloat() / yRange.coerceAtLeast(1)) * chartHeight
                drawLine(
                    color = Color(0xFF1A2E1A),
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 0.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                )
                val tickLabel = if (needsDecimalFormatting) {
                    // Convert from hundredths of a year to years with 2 decimal places
                    String.format("%.2f", tick / 100.0)
                } else {
                    tick.toString()
                }
                drawContext.canvas.nativeCanvas.drawText(
                    tickLabel,
                    chartLeft - 4.dp.toPx(),
                    y + 4.dp.toPx(),
                    textPaint
                )
            }
        }

        // ── X axis labels ─────────────────────────────────────────────────
        val xLabelPaint = android.graphics.Paint().apply {
            color = 0xFF668866.toInt()
            textSize = 9.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val labelInterval = when {
            visTotalDays <= 7 -> 1
            visTotalDays <= 14 -> 2
            visTotalDays <= 30 -> 3
            visTotalDays <= 60 -> 7
            visTotalDays <= 90 -> 14
            visTotalDays <= 180 -> 30
            visTotalDays <= 365 -> 60
            visTotalDays <= 730 -> 90
            else -> (visTotalDays / 8).coerceAtLeast(90)
        }

        val useYearFormat = visTotalDays > 365

        // X-axis gridlines + date labels. Draw extra intervals on each side —
        // enough to cover the current live drag distance — and translate by the
        // drag offset so labels/lines scroll smoothly with the finger (matching
        // the panned series below) instead of popping in/out.
        val labelPadDays = (kotlin.math.abs(dragOffsetPx) / dayWidthPx).toInt() + labelInterval
        clipRect(left = chartLeft, top = chartTop, right = chartRight, bottom = chartBottom + 20.dp.toPx()) {
        translate(left = dragOffsetPx) {
            val startI = -labelPadDays
            val endI = visTotalDays + labelPadDays
            var i = startI - (startI.mod(labelInterval))
            while (i < endI) {
                val date = visStartDate.plusDays(i.toLong())
                val x = chartLeft + (i.toFloat() / (visTotalDays - 1).coerceAtLeast(1)) * chartWidth
                if (x + dragOffsetPx >= chartLeft - dayWidthPx && x + dragOffsetPx <= chartRight + dayWidthPx) {
                    drawContext.canvas.nativeCanvas.drawText(
                        if (useYearFormat) formatYearDate(date) else formatShortDate(date),
                        x,
                        chartBottom + 16.dp.toPx(),
                        xLabelPaint
                    )
                    drawLine(
                        color = Color(0xFF112211),
                        start = Offset(x, chartTop),
                        end = Offset(x, chartBottom),
                        strokeWidth = 0.5.dp.toPx()
                    )
                }
                i += labelInterval
            }
        }
        }

        // ── Zero line (if visible within the range) ─────────────────────────
        if (!useMultiScale && effectiveYMin <= 0 && effectiveYMax >= 0) {
            val zeroY = chartBottom - ((0 - effectiveYMin).toFloat() / yRange.coerceAtLeast(1)) * chartHeight
            drawLine(
                color = Color(0xFF334433),
                start = Offset(chartLeft, zeroY),
                end = Offset(chartRight, zeroY),
                strokeWidth = 1.dp.toPx()
            )
        }

        // ── Each series ───────────────────────────────────────────────────
        // Clip to the plotting area and translate by the live drag offset so the
        // lines/dots pan in real time with the finger. Pad the included data range
        // on each side by however many days the finger has currently dragged (plus
        // a one-day margin) so off-screen points slide smoothly into view during
        // the drag instead of the line abruptly ending at the window edge.
        val dragDays = (kotlin.math.abs(dragOffsetPx) / dayWidthPx).toInt() + 1
        val panStartDate = visStartDate.minusDays(dragDays.toLong())
        val panEndDate = visEndDate.plusDays(dragDays.toLong())
        clipRect(left = chartLeft, top = chartTop, right = chartRight, bottom = chartBottom) {
        translate(left = dragOffsetPx) {
        for (series in seriesData) {
            if (series.data.isEmpty()) continue

            // Only include data points within the (padded) visible range
            val visibleData = series.data.filter { it.date >= panStartDate && it.date <= panEndDate }
            if (visibleData.isEmpty()) continue

            val points = visibleData.map { dp ->
                val dayIdx = ChronoUnit.DAYS.between(visStartDate, dp.date).toInt()
                val x = chartLeft + (dayIdx.toFloat() / (visTotalDays - 1).coerceAtLeast(1)) * chartWidth
                val displayValue = displayValueForMetric(dp, series.metric)
                val y = scaleYForSeries(displayValue, series, chartBottom, chartHeight)
                Offset(x, y)
            }

            // Filled area (fill to zero line if visible, otherwise to bottom)
            if (points.size >= 2) {
                val sc = seriesYScales[series]
                val zeroY = if (sc != null) {
                    if (sc.effectiveMin <= 0 && sc.effectiveMax >= 0) {
                        chartBottom - ((0 - sc.effectiveMin).toFloat() / sc.range) * chartHeight
                    } else {
                        chartBottom
                    }
                } else if (effectiveYMin <= 0 && effectiveYMax >= 0) {
                    chartBottom - ((0 - effectiveYMin).toFloat() / yRange.coerceAtLeast(1)) * chartHeight
                } else {
                    chartBottom
                }
                val areaPath = Path().apply {
                    moveTo(points.first().x, zeroY)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, zeroY)
                    close()
                }
                drawPath(path = areaPath, color = series.color.copy(alpha = 0.08f))
            }

            // Line
            if (points.size >= 2) {
                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(
                    path = linePath,
                    color = series.color.copy(alpha = 0.8f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Dots (only when not too many points)
            if (visTotalDays <= 90) {
                points.forEachIndexed { idx, point ->
                    val dp = visibleData[idx]
                    val isSelected = selectedPoint?.habitName == series.habitName &&
                            selectedPoint?.date == dp.date &&
                            selectedPoint?.metricLabel == series.metricLabel
                    val dotRadius = if (isSelected) 5.dp.toPx() else 2.5.dp.toPx()
                    val displayValue = displayValueForMetric(dp, series.metric)
                    // Show dots for all values when negative values are present
                    val sc = seriesYScales[series]
                    if (displayValue != 0 || isSelected || (sc?.effectiveMin ?: effectiveYMin) < 0) {
                        drawCircle(
                            color = if (isSelected) Color.White else series.color,
                            radius = dotRadius,
                            center = point
                        )
                        if (isSelected) {
                            drawCircle(
                                color = series.color,
                                radius = dotRadius - 1.5.dp.toPx(),
                                center = point
                            )
                        }
                    }
                }
            }

            // 7-day moving average
            if (visibleData.size >= 7 && visTotalDays > 14) {
                drawMovingAverage(
                    data = visibleData,
                    windowSize = 7,
                    color = series.color.copy(alpha = 0.4f),
                    startDate = visStartDate,
                    totalDays = visTotalDays,
                    effectiveYMin = seriesYScales[series]?.effectiveMin ?: effectiveYMin,
                    effectiveYMax = seriesYScales[series]?.effectiveMax ?: effectiveYMax,
                    chartLeft = chartLeft,
                    chartBottom = chartBottom,
                    chartWidth = chartWidth,
                    chartHeight = chartHeight,
                    strokeWidth = 1.5.dp.toPx(),
                    metric = series.metric
                )
            }
        }
        } // translate (live drag pan)
        } // clipRect (plot area)

        // ── Selected point crosshair ──────────────────────────────────────
        selectedPoint?.let { sp ->
            if (sp.date < visStartDate || sp.date > visEndDate) return@let
            val dayIdx = ChronoUnit.DAYS.between(visStartDate, sp.date).toInt()
            val x = chartLeft + (dayIdx.toFloat() / (visTotalDays - 1).coerceAtLeast(1)) * chartWidth
            val matchedSeries = seriesData.find { it.habitName == sp.habitName && it.metricLabel == sp.metricLabel }
            val y = if (matchedSeries != null) {
                scaleYForSeries(sp.value, matchedSeries, chartBottom, chartHeight)
            } else {
                chartBottom - ((sp.value - effectiveYMin).toFloat() / yRange.coerceAtLeast(1)) * chartHeight
            }

            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )

            val valuePaint = android.graphics.Paint().apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 11.dp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = 0xCC1A2E1A.toInt()
                isAntiAlias = true
            }
            // Format fitness age values with 2 decimal places
            val garminType = garminHabitLinks[sp.habitName]?.let { com.example.tail.data.GarminType.fromKey(it) }
            val label = if (garminType == com.example.tail.data.GarminType.FITNESS_AGE ||
                           garminType == com.example.tail.data.GarminType.FITNESS_AGE_DISTANCE) {
                String.format("%.2f", sp.value / 100.0)
            } else {
                sp.value.toString()
            }
            val labelWidth = valuePaint.measureText(label)
            val labelX = x
            val labelY = y - 12.dp.toPx()

            drawContext.canvas.nativeCanvas.drawRoundRect(
                labelX - labelWidth / 2 - 4.dp.toPx(),
                labelY - 12.dp.toPx(),
                labelX + labelWidth / 2 + 4.dp.toPx(),
                labelY + 4.dp.toPx(),
                4.dp.toPx(), 4.dp.toPx(),
                bgPaint
            )
            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, valuePaint)
        }
    }
}

/**
 * Draws a 7-day moving average line on the chart.
 */
private fun DrawScope.drawMovingAverage(
    data: List<HabitViewModel.GraphDataPoint>,
    windowSize: Int,
    color: Color,
    startDate: LocalDate,
    totalDays: Int,
    effectiveYMin: Int,
    effectiveYMax: Int,
    chartLeft: Float,
    chartBottom: Float,
    chartWidth: Float,
    chartHeight: Float,
    strokeWidth: Float,
    metric: String = com.example.tail.data.GRAPH_METRIC_POINTS
) {
    if (data.size < windowSize) return

    val yRange = (effectiveYMax - effectiveYMin).coerceAtLeast(1)
    val maPoints = mutableListOf<Offset>()
    for (i in windowSize - 1 until data.size) {
        val windowAvg = data.subList(i - windowSize + 1, i + 1)
            .map { displayValueForMetric(it, metric).toFloat() }
            .average()
            .toFloat()
        val dp = data[i]
        val dayIdx = ChronoUnit.DAYS.between(startDate, dp.date).toInt()
        val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
        val y = chartBottom - ((windowAvg - effectiveYMin) / yRange) * chartHeight
        maPoints.add(Offset(x, y))
    }

    if (maPoints.size >= 2) {
        val path = Path().apply {
            moveTo(maPoints.first().x, maPoints.first().y)
            for (i in 1 until maPoints.size) lineTo(maPoints[i].x, maPoints[i].y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        )
    }
}

/**
 * Calculate nice Y axis tick values.
 */
private fun calculateYTicks(minValue: Int, maxValue: Int): List<Int> {
    if (maxValue <= minValue) return listOf(minValue, maxValue + 1)

    val range = maxValue - minValue
    val step = when {
        range <= 5 -> 1
        range <= 10 -> 2
        range <= 25 -> 5
        range <= 50 -> 10
        range <= 100 -> 20
        range <= 250 -> 50
        range <= 500 -> 100
        range <= 1000 -> 200
        else -> (range / 5.0).roundToInt().let { s ->
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(s.toDouble()))).toInt()
            if (magnitude > 0) ((s + magnitude - 1) / magnitude) * magnitude else s
        }
    }.coerceAtLeast(1)

    val ticks = mutableListOf<Int>()
    // Start from a tick value at or below minValue
    var tick = if (minValue % step == 0) minValue else minValue - (minValue % step) - step
    while (tick <= maxValue + step) {
        ticks.add(tick)
        tick += step
        if (ticks.size > 20) break
    }
    return ticks
}

// ── Stats Summary ─────────────────────────────────────────────────────────────

@Composable
private fun StatsSummary(
    seriesData: List<GraphSeries>,
    modifier: Modifier = Modifier
) {
    // Hoisted popup state — rendered outside the Row so it never affects stat layout
    var infoPopupText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .background(Color(0xFF0D1E0D), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        for (series in seriesData) {
            if (series.data.isEmpty()) continue
            val values = series.data.map { dp -> displayValueForMetric(dp, series.metric) }
            val nonZeroValues = values.filter { it > 0 }
            val total = values.sum()
            val avg = if (values.isNotEmpty()) values.average() else 0.0
            val max = values.maxOrNull() ?: 0
            val daysActive = nonZeroValues.size
            val totalDays = values.size
            val consistency = if (totalDays > 0) (daysActive * 100.0 / totalDays) else 0.0

            var currentStreak = 0
            for (v in values.reversed()) {
                if (v > 0) currentStreak++ else break
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (seriesData.size > 1) {
                    Text(
                        text = if (series.metricLabel != "Points" && series.metricLabel != "Value 1")
                            "${series.habitName} (${series.metricLabel})"
                        else series.habitName,
                        color = series.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(100.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatChip("Total", total.toString(), Color(0xFF88CCFF),
                    onInfoClick = { infoPopupText = "Sum of all daily values in the selected time period. Days with no activity count as 0." })
                StatChip("Avg", "%.1f".format(avg), Color(0xFF81C784),
                    onInfoClick = { infoPopupText = "Calendar-day average: total ÷ number of days in the period. Days with no activity are included as 0, matching the (current) values in the info panel below." })
                StatChip("Max", max.toString(), Color(0xFFFFD54F),
                    onInfoClick = { infoPopupText = "The highest single-day value recorded within the selected time period." })
                StatChip("Active", "$daysActive/$totalDays", Color(0xFFBA68C8),
                    onInfoClick = { infoPopupText = "Days with non-zero values out of total days in the period. Shows how many days you actually did this habit." })
                StatChip("${consistency.roundToInt()}%", "cons.", Color(0xFFFF8A65),
                    onInfoClick = { infoPopupText = "Percentage of days in the period where this habit had a non-zero value. 100% means you did it every single day." })
                if (currentStreak > 0) {
                    StatChip("🔥$currentStreak", "streak", Color(0xFFE57373),
                        onInfoClick = { infoPopupText = "Current consecutive days with non-zero values, counting backwards from the most recent day shown." })
                }
            }

            if (seriesData.size > 1 && series != seriesData.last()) {
                HorizontalDivider(
                    color = Color(0xFF1A2E1A),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }

    // Single info popup — rendered here (outside the Row) so it never changes the stat layout
    infoPopupText?.let { text ->
        Popup(
            onDismissRequest = { infoPopupText = null },
            properties = PopupProperties(focusable = true)
        ) {
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .background(Color(0xFF1A2E1A), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF446644), RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .clickable { infoPopupText = null }
            ) {
                Text(
                    text = text,
                    color = Color(0xFFCCEECC),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, color: Color, onInfoClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .then(
                if (onInfoClick != null)
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onInfoClick() }
                else Modifier
            )
    ) {
        Text(text = value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color(0xFF556655), fontSize = 8.sp)
    }
}

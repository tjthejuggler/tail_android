package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.ui.map.HopStat
import com.example.tail.ui.map.PeriodStat
import com.example.tail.ui.map.PlaceDays
import com.example.tail.ui.map.StatsBarChart
import com.example.tail.ui.map.StatsSectionCard
import com.example.tail.ui.map.StatChip
import com.example.tail.ui.map.TopPlaceRow
import com.example.tail.ui.map.TravelStatsData
import com.example.tail.ui.map.computeTravelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ── Palette (matches AppStatsScreen) ──────────────────────────────────────────

private val LabelColor = Color(0xFFADD8E6)
private val ValueColor = Color.White
private val DimColor = Color(0xFF888888)
private val DividerColor = Color(0xFF333344)
private val GoldValue = Color(0xFFFFD700)
private val GreenValue = Color(0xFF80FF80)
private val LinkColor = Color(0xFF66CCFF)
private val PopupBg = Color(0xFF1A1A2E)

// Chart accents — drawn from the same family as the graphs screen palette.
private val CountriesColor = Color(0xFF4FC3F7)  // light blue
private val CitiesColor = Color(0xFFFF8A65)     // orange
private val ContinentsColor = Color(0xFFBA68C8) // purple
private val DistanceColor = Color(0xFF81C784)   // green

private val STATS_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val MONTH_FMT = DateTimeFormatter.ofPattern("MMM yyyy")

/** How many leaderboard rows are visible before "Show all" expands the list. */
private const val COLLAPSED_ROWS = 8

/**
 * Chart time-range options. Each fixed range shows a window of that many
 * buckets (1M = 30 daily, 1Y = 12 monthly, 5Y/10Y = monthly or yearly);
 * the window can be panned back in time by swiping the charts horizontally.
 * ALL shows the entire series. 1M and 1Y force their natural granularity.
 */
private enum class StatsRange(val label: String, val years: Int?) {
    ONE_MONTH("1M", null),
    ONE_YEAR("1Y", 1),
    FIVE_YEARS("5Y", 5),
    TEN_YEARS("10Y", 10),
    ALL("All", null)
}

/** Content of the bar-detail popup shown when a bar is tapped. */
private sealed interface PeriodPopup {
    val title: String
}
private data class PlacesPopup(
    override val title: String,
    val entries: List<Pair<String, LocalDate>>
) : PeriodPopup
private data class DistancePopup(
    override val title: String,
    val totalKm: Double,
    val hops: List<HopStat>
) : PeriodPopup

/**
 * Travel & map statistics screen: charts of new countries / cities /
 * continents and distance traveled over a selectable, pannable time range
 * (1M / 1Y / 5Y / 10Y / All), expandable leaderboards of most-visited
 * places, and assorted records (longest stay, biggest hop, lat extremes).
 *
 * Reached from the world-map screen's stats button. All heavy aggregation
 * runs off the main thread in one [computeTravelStats] pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStatsScreen(
    viewModel: HabitViewModel,
    onNavigateBack: () -> Unit
) {
    var stats by remember { mutableStateOf<TravelStatsData?>(null) }
    var range by remember { mutableStateOf(StatsRange.ONE_YEAR) }
    // Yearly vs monthly granularity — only honoured for 5Y/10Y/All ranges.
    var yearlyGranularity by remember { mutableStateOf(false) }
    // How many buckets the visible window is shifted back from the newest
    // data (0 = anchored at the latest period). Driven by chart swipes.
    var windowOffset by remember { mutableStateOf(0) }
    // Per-section "Show all" expansion for the leaderboards.
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    // Bar-detail popup: which chart + bar index is selected.
    var popup by remember { mutableStateOf<PeriodPopup?>(null) }
    var selectedChart by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val locationVersion = viewModel.locationDataVersion

    LaunchedEffect(locationVersion) {
        stats = withContext(Dispatchers.Default) {
            computeTravelStats(
                labelsByDate = viewModel.getAllStoredLabelsParsed(),
                coordsByDate = viewModel.getAllStoredCoordsParsed(),
                secondariesByDate = viewModel.getAllSecondaryLocations(),
                ignoredCountries = viewModel.getIgnoredCountryNames()
            )
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Travel Stats", color = GoldValue, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val data = stats
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Crunching your travels…", color = DimColor, fontSize = 14.sp)
            }
        } else if (!data.hasData) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No location history yet.\nThe map screen records where you are each day.",
                    color = DimColor,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // ── Series + window selection ─────────────────────────────────
            val useYearly = yearlyGranularity && range != StatsRange.ONE_MONTH && range != StatsRange.ONE_YEAR
            val series = when {
                range == StatsRange.ONE_MONTH -> data.daily
                useYearly -> data.yearly
                else -> data.monthly
            }
            val windowBuckets = when {
                range == StatsRange.ONE_MONTH -> 30
                range == StatsRange.ONE_YEAR -> 12
                range == StatsRange.ALL -> series.size
                useYearly -> range.years ?: series.size
                else -> (range.years ?: 0) * 12
            }
            // Re-anchor the window whenever the range or granularity changes.
            LaunchedEffect(range, useYearly) { windowOffset = 0 }
            // A pan slides the window under the bars — any open bar popup
            // would now describe a different bucket, so dismiss it.
            LaunchedEffect(windowOffset) {
                popup = null; selectedChart = null; selectedIndex = null
            }
            val maxOffset = (series.size - windowBuckets).coerceAtLeast(0)
            val canPan = maxOffset > 0
            val onPan: (Int) -> Unit = { buckets ->
                windowOffset = (windowOffset + buckets).coerceIn(0, maxOffset)
            }
            val visible = remember(series, windowOffset, windowBuckets) {
                val end = (series.size - windowOffset).coerceAtLeast(1)
                val start = (end - windowBuckets).coerceAtLeast(0)
                series.subList(start, end)
            }
            val labels = visible.map { it.label }
            val unitWord = when {
                range == StatsRange.ONE_MONTH -> "day"
                useYearly -> "year"
                else -> "month"
            }
            // Slightly smaller axis text on the dense "All" charts.
            val axisSize = if (range == StatsRange.ALL) 9.sp else 11.sp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Summary chips (all-time totals) ───────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChip(
                        value = data.totalCountries.toString(),
                        label = "Countries",
                        accent = CountriesColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        value = data.totalCities.toString(),
                        label = "Cities",
                        accent = CitiesColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        value = data.totalContinents.toString(),
                        label = "Continents",
                        accent = ContinentsColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        value = formatKmShort(data.totalDistanceKm),
                        label = "Traveled",
                        accent = DistanceColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                // ── Time-range selector ───────────────────────────────────
                Spacer(modifier = Modifier.height(10.dp))
                SegmentedControl(
                    options = StatsRange.entries.map { it.label },
                    selectedIndex = StatsRange.entries.indexOf(range),
                    onSelect = {
                        range = StatsRange.entries[it]
                        popup = null; selectedChart = null; selectedIndex = null
                    }
                )

                // ── Granularity toggle (hidden for 1M/1Y — implied) ────────
                if (range != StatsRange.ONE_MONTH && range != StatsRange.ONE_YEAR) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SegmentedControl(
                        options = listOf("Monthly", "Yearly"),
                        selectedIndex = if (useYearly) 1 else 0,
                        onSelect = {
                            yearlyGranularity = it == 1
                            popup = null; selectedChart = null; selectedIndex = null
                        }
                    )
                }

                // ── Charts ────────────────────────────────────────────────
                Spacer(modifier = Modifier.height(6.dp))
                ChartSection(
                    title = "🗺 New countries",
                    caption = "First-time countries discovered per $unitWord",
                    chartId = "countries",
                    visible = visible,
                    labels = labels,
                    barColor = CountriesColor,
                    axisTextSize = axisSize,
                    valueOf = { it.newCountries.toFloat() },
                    selectedChart = selectedChart,
                    selectedIndex = selectedIndex,
                    canPan = canPan,
                    onPan = onPan,
                    onTap = { idx ->
                        val p = visible.getOrNull(idx)
                        if (p != null && p.countryFirsts.isNotEmpty()) {
                            selectedChart = "countries"; selectedIndex = idx
                            popup = PlacesPopup(
                                title = periodTitle(range, useYearly, p) + " · ${p.newCountries} new",
                                entries = p.countryFirsts
                            )
                        } else {
                            selectedChart = null; selectedIndex = null; popup = null
                        }
                    }
                )
                ChartSection(
                    title = "🏙 New cities",
                    caption = "First-time cities discovered per $unitWord",
                    chartId = "cities",
                    visible = visible,
                    labels = labels,
                    barColor = CitiesColor,
                    axisTextSize = axisSize,
                    valueOf = { it.newCities.toFloat() },
                    selectedChart = selectedChart,
                    selectedIndex = selectedIndex,
                    canPan = canPan,
                    onPan = onPan,
                    onTap = { idx ->
                        val p = visible.getOrNull(idx)
                        if (p != null && p.cityFirsts.isNotEmpty()) {
                            selectedChart = "cities"; selectedIndex = idx
                            popup = PlacesPopup(
                                title = periodTitle(range, useYearly, p) + " · ${p.newCities} new",
                                entries = p.cityFirsts
                            )
                        } else {
                            selectedChart = null; selectedIndex = null; popup = null
                        }
                    }
                )
                ChartSection(
                    title = "🌐 New continents",
                    caption = "First-time continents reached per $unitWord",
                    chartId = "continents",
                    visible = visible,
                    labels = labels,
                    barColor = ContinentsColor,
                    axisTextSize = axisSize,
                    valueOf = { it.newContinents.toFloat() },
                    selectedChart = selectedChart,
                    selectedIndex = selectedIndex,
                    canPan = canPan,
                    onPan = onPan,
                    onTap = { idx ->
                        val p = visible.getOrNull(idx)
                        if (p != null && p.continentFirsts.isNotEmpty()) {
                            selectedChart = "continents"; selectedIndex = idx
                            popup = PlacesPopup(
                                title = periodTitle(range, useYearly, p) + " · ${p.newContinents} new",
                                entries = p.continentFirsts
                            )
                        } else {
                            selectedChart = null; selectedIndex = null; popup = null
                        }
                    }
                )
                ChartSection(
                    title = "✈️ Distance traveled",
                    caption = "Great-circle km between consecutive tracked days",
                    chartId = "distance",
                    visible = visible,
                    labels = labels,
                    barColor = DistanceColor,
                    axisTextSize = axisSize,
                    valueOf = { it.distanceKm.toFloat() },
                    valueFormatter = { formatKmShort(it.toDouble()) },
                    selectedChart = selectedChart,
                    selectedIndex = selectedIndex,
                    canPan = canPan,
                    onPan = onPan,
                    onTap = { idx ->
                        val p = visible.getOrNull(idx)
                        if (p != null && p.distanceKm > 0.0) {
                            selectedChart = "distance"; selectedIndex = idx
                            popup = DistancePopup(
                                title = "${periodTitle(range, useYearly, p)} · ${formatKmShort(p.distanceKm)}",
                                totalKm = p.distanceKm,
                                hops = p.hops
                            )
                        } else {
                            selectedChart = null; selectedIndex = null; popup = null
                        }
                    }
                )

                // ── Leaderboards (expandable) ─────────────────────────────
                ExpandablePlaceSection(
                    title = "🏆 Most-visited countries",
                    sectionKey = "countries",
                    places = data.topCountries,
                    accent = CountriesColor,
                    expandedSections = expandedSections
                )
                ExpandablePlaceSection(
                    title = "🏙 Most-visited cities",
                    sectionKey = "cities",
                    places = data.topCities,
                    accent = CitiesColor,
                    expandedSections = expandedSections
                )
                ExpandablePlaceSection(
                    title = "🌐 Days per continent",
                    sectionKey = "continents",
                    places = data.continentDays,
                    accent = ContinentsColor,
                    expandedSections = expandedSections
                )

                // ── Records ───────────────────────────────────────────────
                StatsSectionCard(title = "📜 Records") {
                    StatRow("Days with location", data.daysTracked.toString())
                    data.firstDate?.let {
                        StatRow("First tracked day", it.format(STATS_DATE_FMT))
                    }
                    data.lastDate?.let {
                        StatRow("Latest tracked day", it.format(STATS_DATE_FMT))
                    }
                    StatRow("Unique places", data.uniquePlaces.toString())
                    if (data.longestStayPlace != null && data.longestStayDays > 0) {
                        StatRow(
                            "Longest stay",
                            "${data.longestStayDays} d — ${data.longestStayPlace}",
                            valueColor = GreenValue
                        )
                    }
                    data.biggestHop?.let { hop ->
                        StatRow(
                            "Biggest single hop",
                            "${formatKmShort(hop.km)} — ${hop.toPlace ?: "?"} (${hop.toDate.format(STATS_DATE_FMT)})",
                            valueColor = GoldValue
                        )
                    }
                    data.northernmost?.let { n ->
                        StatRow("Northernmost", "${n.place ?: ""} ${formatLat(n.lat)}".trim())
                    }
                    data.southernmost?.let { s ->
                        StatRow("Southernmost", "${s.place ?: ""} ${formatLat(s.lat)}".trim())
                    }
                    if (data.secondaryPings > 0) {
                        StatRow("Extra places logged", data.secondaryPings.toString())
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ── Bar-detail popup (drawn above everything) ─────────────────────────
    val currentPopup = popup
    if (currentPopup != null) {
        PeriodInfoPopup(
            popup = currentPopup,
            onDismiss = {
                popup = null; selectedChart = null; selectedIndex = null
            }
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Human title for a tapped bucket, e.g. "Aug 2024" / "2024" / "Aug 14, 2026". */
private fun periodTitle(range: StatsRange, useYearly: Boolean, p: PeriodStat): String = when {
    range == StatsRange.ONE_MONTH ->
        LocalDate.ofEpochDay(p.sortKey.toLong()).format(STATS_DATE_FMT)
    useYearly -> p.sortKey.toString()
    else -> LocalDate.of(p.sortKey / 12, p.sortKey % 12 + 1, 1).format(MONTH_FMT)
}

// ── Local components ──────────────────────────────────────────────────────────

/** One chart section wired to the shared window/pan/selection state. */
@Composable
private fun ChartSection(
    title: String,
    caption: String,
    chartId: String,
    visible: List<PeriodStat>,
    labels: List<String>,
    barColor: Color,
    valueOf: (PeriodStat) -> Float,
    valueFormatter: (Float) -> String = { it.roundToInt().toString() },
    axisTextSize: TextUnit = 11.sp,
    selectedChart: String?,
    selectedIndex: Int?,
    canPan: Boolean,
    onPan: (Int) -> Unit,
    onTap: (Int) -> Unit
) {
    StatsSectionCard(title = title) {
        Text(
            text = caption,
            color = DimColor,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))
        StatsBarChart(
            values = visible.map(valueOf),
            labels = labels,
            barColor = barColor,
            valueFormatter = valueFormatter,
            axisTextSize = axisTextSize,
            selectedIndex = if (selectedChart == chartId) selectedIndex else null,
            canPan = canPan,
            onPanBuckets = onPan,
            onBarTap = onTap
        )
    }
}

/** Dark segmented pill control (used for the range and granularity toggles). */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12121F), RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { i, option ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) GoldValue else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    color = if (selected) Color(0xFF0A0A0A) else DimColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Leaderboard section showing the top [COLLAPSED_ROWS] places by default,
 * with a "Show all (N)" / "Show less" toggle in the header.
 */
@Composable
private fun ExpandablePlaceSection(
    title: String,
    sectionKey: String,
    places: List<PlaceDays>,
    accent: Color,
    expandedSections: MutableMap<String, Boolean>
) {
    if (places.isEmpty()) return
    val expanded = expandedSections[sectionKey] == true
    val visible = if (expanded) places else places.take(COLLAPSED_ROWS)
    val maxDays = places.first().days

    StatsSectionCard(
        title = title,
        trailing = {
            if (places.size > COLLAPSED_ROWS) {
                Text(
                    text = if (expanded) "Show less" else "Show all (${places.size})",
                    color = LinkColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable {
                            expandedSections[sectionKey] = !expanded
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    ) {
        visible.forEachIndexed { i, place ->
            TopPlaceRow(
                rank = i + 1,
                name = place.name,
                days = place.days,
                maxDays = maxDays,
                accent = accent
            )
        }
    }
}

/** Popup listing the places/values behind one tapped chart bar. */
@Composable
private fun PeriodInfoPopup(popup: PeriodPopup, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(PopupBg, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = popup.title,
                color = GoldValue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(4.dp))
            when (popup) {
                is PlacesPopup -> {
                    if (popup.entries.isEmpty()) {
                        Text("Nothing new this period", color = DimColor, fontSize = 12.sp)
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            popup.entries.forEach { (name, date) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(name, color = LabelColor, fontSize = 13.sp)
                                    Text(
                                        date.format(STATS_DATE_FMT),
                                        color = DimColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
                is DistancePopup -> {
                    Text(
                        "Total: ${formatKmShort(popup.totalKm)}",
                        color = ValueColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    if (popup.hops.isNotEmpty()) {
                        Text(
                            "Biggest hops",
                            color = DimColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            popup.hops.take(10).forEach { hop ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        hop.toPlace ?: "—",
                                        color = LabelColor,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        "${formatKmShort(hop.km)} · ${hop.toDate.format(STATS_DATE_FMT)}",
                                        color = DimColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { onDismiss() }
                    .background(Color(0xFF2A2A4A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Close", color = LinkColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Label → value line inside a section, styled like the App Stats rows. */
@Composable
private fun StatRow(label: String, value: String, valueColor: Color = ValueColor) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = LabelColor, fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

/** 842 → "842 km", 12_345 → "12.3k km". */
private fun formatKmShort(km: Double): String = when {
    km >= 100_000.0 -> "${(km / 1000).roundToInt()}k km"
    km >= 1_000.0 -> {
        val v = km / 1000.0
        if (v >= 100.0) "${v.roundToInt()}k km" else "${String.format("%.1f", v)}k km"
    }
    else -> "${km.roundToInt()} km"
}

/** 51.5 → "51.5°N", -33.9 → "33.9°S". */
private fun formatLat(lat: Double): String {
    val hemi = if (lat >= 0) "N" else "S"
    return String.format("%.1f°%s", kotlin.math.abs(lat), hemi)
}

package com.example.tail.ui

import android.app.Activity
import android.widget.Toast
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.DayStats
import com.example.tail.data.SecondaryLocation
import com.example.tail.ui.map.DayClock
import com.example.tail.ui.map.WorldLandData
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 4-tuple helper for the LaunchedEffect that loads map data in one pass. */
private data class Result4<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D
)

// Display formatting for the map screen.
private val MAP_DATE_FMT = DateTimeFormatter.ofPattern("yyyy, MMM dd, EEE")

/**
 * Fuzzy location-label comparison: two labels are considered the "same place"
 * if their last two comma-separated parts match (e.g. "Dublin, County Dublin,
 * Leinster, Ireland" ≈ "Dublin, Ireland" because both end with "Dublin, Ireland").
 * Returns true when the labels refer to the same place.
 */
private fun isSameLocationLabel(a: String?, b: String?): Boolean {
    if (a == null || b == null) return a == b
    val partsA = a.split(",").map { it.trim() }
    val partsB = b.split(",").map { it.trim() }
    val lastTwoA = partsA.takeLast(2).joinToString(", ")
    val lastTwoB = partsB.takeLast(2).joinToString(", ")
    return lastTwoA == lastTwoB
}

// Available playback speeds in days/sec. -1f represents "Auto" mode.
private val PLAY_SPEEDS = listOf(0.5f, 1f, 2f, 5f, 15f, 30f, 60f, 120f, -1f)
private const val DEFAULT_SPEED_INDEX = 8  // Auto

/**
 * Maximum allowed marker travel speed, expressed as a fraction of the map's
 * width per second. At 0.6 the little man can cross 60% of the map width
 * (≈ continent-spanning) per second — fast enough to feel snappy but slow
 * enough to never visually teleport. The playback loop will *wait* longer
 * than the nominal day-tick when the marker needs more time to arrive.
 */
private const val MAX_MARKER_SPEED_FRAC_PER_SEC = 0.6f

/**
 * Floor for how short a single-day animation can be. Even short hops get at
 * least this much animation time so they don't snap visibly.
 */
private const val MIN_MARKER_ANIM_MS = 60L

/**
 * Returns the animation duration (ms) needed to traverse [distancePx] without
 * exceeding [MAX_MARKER_SPEED_FRAC_PER_SEC] of [mapWidthPx] per second.
 * Returns 0 when [mapWidthPx] is 0 (map not measured yet).
 */
private fun requiredAnimMs(distancePx: Float, mapWidthPx: Float): Long {
    if (mapWidthPx <= 0f) return 0L
    val maxPxPerSec = MAX_MARKER_SPEED_FRAC_PER_SEC * mapWidthPx
    if (maxPxPerSec <= 0f) return 0L
    return ((distancePx / maxPxPerSec) * 1000f).toLong()
}

/**
 * Full-screen world-map "where I was" timeline screen.
 *
 * • Forces landscape orientation while visible.
 * • Shows continent outlines via [WorldMapBackground].
 * • Plots a marker for each day with known coords; the *currently selected*
 *   day's marker is highlighted and animates as the user drags / plays the
 *   timeline.
 * • Side panel shows simple stats for the selected day (habits done, points,
 *   streak) and the location label.
 * • Selected date is shared with the main grid via the same [HabitViewModel],
 *   so navigating in/out preserves the day in both directions.
 */
@Composable
fun MapScreen(
    viewModel: HabitViewModel,
    onNavigateBack: () -> Unit
) {
    // ── Force landscape while this screen is visible ────────────────────────
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // ── Immersive mode: hide status bar, navigation bar, and clock ──
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            // Restore whatever orientation policy was in effect before we entered.
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
            // Restore system bars
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    // Wrap back navigation so we always sync the selected date to the grid
    // before popping the back stack. Also intercept the system back gesture.
    val handleBack: () -> Unit = {
        viewModel.navigateToDate(viewModel.selectedDate.value)
        onNavigateBack()
    }
    BackHandler(onBack = handleBack)

    val selectedDate by viewModel.selectedDate.collectAsState()

    // ── Snapshot ALL day-coords ONCE on entry, OFF the main thread.
    // Reading SharedPrefs + parsing thousands of JSON entries on the UI thread
    // would freeze for many seconds and trigger an ANR (the Compose `remember`
    // block runs on the composition thread, but the JSON parse work is heavy
    // enough to lock the frame). We do it in a LaunchedEffect via withContext.
    var coordsByDate by remember { mutableStateOf<Map<LocalDate, Pair<Double, Double>>>(emptyMap()) }
    var dataLoaded by remember { mutableStateOf(false) }
    // Sorted (date, country) timeline → enables O(N) "countries up to date X"
    // scans without touching SharedPrefs on every slider tick. Re-loaded only
    // when the user adds/edits a location (locationDataVersion bumps).
    var countryTimeline by remember { mutableStateOf<List<Pair<LocalDate, String>>>(emptyList()) }
    // Per-date accent colours — each dot is locked to the colour of the day it
    // represents, so dots don't all shift when the current day changes.
    var dotColorsByDate by remember { mutableStateOf<Map<LocalDate, Color>>(emptyMap()) }
    // Secondary locations per date — logged each time the app is opened.
    var secondaryByDate by remember { mutableStateOf<Map<LocalDate, List<SecondaryLocation>>>(emptyMap()) }
    val locationVersion = viewModel.locationDataVersion
    LaunchedEffect(locationVersion) {
        val (coords, countries, colors, secondaries) = withContext(Dispatchers.Default) {
            // Single SharedPrefs read + single JSON parse → O(N) instead of
            // O(N²) date-by-date lookups.
            val c = viewModel.getAllStoredCoordsParsed()
            val ct = viewModel.buildCountryTimeline()
            // Compute each date's accent colour from its own monthly average.
            val dc = c.keys.associateWith { date ->
                accentColorForPoints(kotlin.math.round(viewModel.getDayStatsLight(date).monthlyAverage).toInt())
            }
            // Load secondary locations in one pass
            val sec = viewModel.getAllSecondaryLocations()
                .mapNotNull { (dateStr, list) ->
                    runCatching { LocalDate.parse(dateStr) to list }.getOrNull()
                }
                .toMap()
            Result4(c, ct, dc, sec)
        }
        coordsByDate = coords
        countryTimeline = countries
        dotColorsByDate = colors
        secondaryByDate = secondaries
        dataLoaded = true
    }

    val firstDate = remember(coordsByDate) {
        coordsByDate.keys.minOrNull() ?: selectedDate
    }
    val lastDate = remember(coordsByDate) {
        coordsByDate.keys.maxOrNull() ?: selectedDate
    }
    val totalDays = remember(firstDate, lastDate) {
        java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate)
            .coerceAtLeast(0L).toInt()
    }

    // Resolve the marker's coords for the *current* day. If the selected day
    // itself has no coords, fall back to the nearest preceding day with
    // coords — that's the user's last known location. Empty map → null.
    // During secondary-location playback, secondaryPlaybackCoords overrides this.
    var secondaryPlaybackCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val currentDisplayCoords = remember(selectedDate, coordsByDate, secondaryPlaybackCoords) {
        secondaryPlaybackCoords
            ?: coordsByDate[selectedDate]
            ?: coordsByDate.entries
                .filter { it.key.isBefore(selectedDate) }
                .maxByOrNull { it.key }
                ?.value
            ?: coordsByDate.entries.minByOrNull { it.key }?.value
    }

    // ── "All" mode: show secondary locations on map + clock ─────────────────
    var showAll by remember { mutableStateOf(true) }

    // ── Secondary stepping state ────────────────────────────────────────────
    // null = currently viewing the day's PRIMARY (whole-number label).
    // 0..N-1 = currently viewing the Nth distinct secondary for the day.
    // Driven by the left/right arrow buttons.
    var secondaryStepIndex by remember { mutableStateOf<Int?>(null) }
    // When the BACKWARD arrow crosses to the previous day we want to land on
    // that day's LAST distinct secondary. We park the desired index here so
    // it survives the date-change auto-reset below.
    var pendingStepIndexOnDateChange by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedDate) {
        secondaryStepIndex = pendingStepIndexOnDateChange
        pendingStepIndexOnDateChange = null
    }

    // ── Clock state ────────────────────────────────────────────────────────
    // Derived from secondaryStepIndex (and overridden by the auto-play loop).
    // null → primary view (clock spins or shows nothing).
    var clockTimeMinutes by remember { mutableStateOf<Int?>(null) }
    var clockSpinPhase by remember { mutableStateOf(0f) }

    // ── Manual secondary location entry dialog ─────────────────────────────
    var showAddLocationDialog by remember { mutableStateOf(false) }

    // ── Playback state ──────────────────────────────────────────────────────
    var isPlaying by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableStateOf(DEFAULT_SPEED_INDEX) }
    val speed = PLAY_SPEEDS[speedIndex]
    val scope = rememberCoroutineScope()

    // Measured map size — populated by WorldMapWithMarker once the canvas
    // is laid out. Used by the playback loop to compute how much real time
    // the marker needs to travel between two days without exceeding its
    // max speed (so the little man never visually teleports).
    var mapSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(isPlaying, speed, mapSize) {
        if (!isPlaying) return@LaunchedEffect

        // Auto-play owns secondary marker + clock writes directly; clear any
        // manual step-index so it doesn't fight us through the derivation
        // effect once playback finishes.
        secondaryStepIndex = null

        val isAuto = speed == -1f
        val effectiveSpeed = if (isAuto) 240f else speed
        // Nominal time per day at the current speed.
        val msPerDay = (1000f / effectiveSpeed).toLong().coerceAtLeast(15L)
        // Speed for secondary locations — always fast (quick traversal).
        val msPerSecondary = (1000f / effectiveSpeed).toLong().coerceAtLeast(10L)
        
        while (isPlaying) {
            val cur = viewModel.selectedDate.value
            if (!cur.isBefore(lastDate)) {
                isPlaying = false
                break
            }

            // ── Traverse secondary locations for the current day at quick speed ──
            // Only when "All" mode is active. Filter by GPS distance (≥250m
            // from the day's primary AND from each previously-shown secondary)
            // so we don't dwell on labels that are physically the same place.
            if (showAll) {
                val curSecondaries = secondaryByDate[cur]?.sortedBy { it.timeMinutes }.orEmpty()
                val curPrimaryCoords = coordsByDate[cur]
                val differentSecondaries = run {
                    val kept = mutableListOf<SecondaryLocation>()
                    for (sec in curSecondaries) {
                        val tooClosePrimary = curPrimaryCoords != null &&
                            com.example.tail.data.haversineMeters(
                                curPrimaryCoords.first, curPrimaryCoords.second, sec.lat, sec.lon
                            ) < 250.0
                        if (tooClosePrimary) continue
                        val tooCloseKept = kept.any {
                            com.example.tail.data.haversineMeters(
                                it.lat, it.lon, sec.lat, sec.lon
                            ) < 250.0
                        }
                        if (tooCloseKept) continue
                        kept.add(sec)
                    }
                    kept
                }
                if (differentSecondaries.isNotEmpty()) {
                    // Show the whole-number day briefly before stepping into secondaries
                    clockTimeMinutes = null
                    secondaryPlaybackCoords = null
                    delay(msPerSecondary)
                    for (sec in differentSecondaries) {
                        if (!isPlaying) break
                        secondaryPlaybackCoords = Pair(sec.lat, sec.lon)
                        clockTimeMinutes = sec.timeMinutes
                        delay(msPerSecondary)
                    }
                    secondaryPlaybackCoords = null
                }
            }

            // Compute the next day's display coords (with the same fallback
            // logic as currentDisplayCoords) so we can size the wait window
            // to "however long the marker needs to traverse the distance".
            val nextDate = cur.plusDays(1)
            val curCoords = coordsByDate[cur]
                ?: coordsByDate.entries.filter { it.key.isBefore(cur) }
                    .maxByOrNull { it.key }?.value
            val nextCoords = coordsByDate[nextDate]
                ?: coordsByDate.entries.filter { it.key.isBefore(nextDate) }
                    .maxByOrNull { it.key }?.value
            val travelMs = if (curCoords != null && nextCoords != null && mapSize.width > 0f) {
                val (lat1, lon1) = curCoords
                val (lat2, lon2) = nextCoords
                // Use shortest longitude difference (world wrapping)
                var lonDiff = lon2 - lon1
                if (lonDiff > 180.0) lonDiff -= 360.0
                if (lonDiff < -180.0) lonDiff += 360.0
                val dx = (lonDiff / 360.0 * mapSize.width).toFloat()
                val dy = latToY(lat2, mapSize.height) - latToY(lat1, mapSize.height)
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                requiredAnimMs(dist, mapSize.width)
            } else 0L
            
            // Check for location change in Auto mode — only novel MAIN
            // locations trigger the slow pause, not secondary locations.
            var autoPauseMs = 0L
            if (isAuto) {
                val curLabel = viewModel.getLocationLabelForDate(cur)
                val nextLabel = viewModel.getLocationLabelForDate(nextDate)
                
                val allLabels = viewModel.getAllStoredLabels()
                
                val effectiveCurLabel = curLabel ?: allLabels.entries
                    .mapNotNull { (k, v) -> runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.let { it to v } }
                    .filter { (d, _) -> d.isBefore(cur) }
                    .maxByOrNull { (d, _) -> d }?.second ?: allLabels.values.firstOrNull()
                    
                val effectiveNextLabel = nextLabel ?: allLabels.entries
                    .mapNotNull { (k, v) -> runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.let { it to v } }
                    .filter { (d, _) -> d.isBefore(nextDate) }
                    .maxByOrNull { (d, _) -> d }?.second ?: allLabels.values.firstOrNull()

                if (nextLabel != null && effectiveCurLabel != null) {
                    val curParts = effectiveCurLabel.split(",").map { it.trim() }
                    val nextParts = nextLabel.split(",").map { it.trim() }
                    
                    val curLastTwo = curParts.takeLast(2).joinToString(", ")
                    val nextLastTwo = nextParts.takeLast(2).joinToString(", ")
                    
                    if (curLastTwo != nextLastTwo) {
                        autoPauseMs = 500L
                    }
                } else if (nextLabel != null && effectiveCurLabel == null) {
                    autoPauseMs = 500L
                }
            }

            // Wait at least the nominal day-tick, but stretch out for long
            // jumps so the marker can finish its slide.
            val wait = kotlin.math.max(msPerDay, travelMs) + autoPauseMs
            delay(wait)
            // navigateToDate clamps to today and triggers the same grid rebuild.
            viewModel.navigateToDate(cur.plusDays(1))
        }
        // Clean up secondary playback state when playback stops
        secondaryPlaybackCoords = null
    }

    // ── Day-driven accent colour ────────────────────────────────────────────
    // Light stats (points + monthly avg) are computed on every tick for the accent.
    // Full stats (streak/anti-streak) are debounced: only computed after the user
    // pauses on a day for 400ms, so rapid playback stays smooth.
    val lightStats = remember(selectedDate) { viewModel.getDayStatsLight(selectedDate) }
    val accent = remember(lightStats.monthlyAverage) { accentColorForPoints(kotlin.math.round(lightStats.monthlyAverage).toInt()) }

    // ── Day secondaries (raw + distinct-from-primary, GPS distance based) ──
    // `daySecondaries` = ALL secondaries logged for the day (sorted by time).
    // `distinctDaySecondaries` = only those that are physically distant from
    // the day's primary coords AND from any earlier-in-the-day secondary
    // we'll be showing — this is what the user steps through and what
    // determines whether the day has a decimal in the "N / M" label.
    val daySecondaries = remember(selectedDate, secondaryByDate) {
        secondaryByDate[selectedDate]?.sortedBy { it.timeMinutes } ?: emptyList()
    }
    val distinctDaySecondaries = remember(selectedDate, daySecondaries, coordsByDate) {
        val primary = coordsByDate[selectedDate]
        val kept = mutableListOf<SecondaryLocation>()
        for (sec in daySecondaries) {
            val tooCloseToPrimary = primary != null &&
                com.example.tail.data.haversineMeters(primary.first, primary.second, sec.lat, sec.lon) < 250.0
            if (tooCloseToPrimary) continue
            val tooCloseToKept = kept.any {
                com.example.tail.data.haversineMeters(it.lat, it.lon, sec.lat, sec.lon) < 250.0
            }
            if (tooCloseToKept) continue
            kept.add(sec)
        }
        kept
    }

    // Drive the clock + secondary marker from secondaryStepIndex (manual
    // stepping); auto-play has its own override branch. Suppressed while
    // isPlaying so the play loop's direct writes aren't clobbered.
    LaunchedEffect(selectedDate, secondaryStepIndex, distinctDaySecondaries, showAll, isPlaying) {
        if (isPlaying) return@LaunchedEffect
        val idx = secondaryStepIndex
        val active = showAll && idx != null && idx in distinctDaySecondaries.indices
        if (active) {
            val sec = distinctDaySecondaries[idx!!]
            clockTimeMinutes = sec.timeMinutes
            secondaryPlaybackCoords = Pair(sec.lat, sec.lon)
        } else {
            clockTimeMinutes = null
            secondaryPlaybackCoords = null
        }
    }

    // Fast 24h clock sweep when there are no secondary locations.
    // Does one full day cycle (0→1439 min) in ~1.5s on each day change, then stops.
    LaunchedEffect(selectedDate) {
        clockSpinPhase = 0f
        val hasSecondaries = showAll && secondaryByDate[selectedDate]?.isNotEmpty() == true
        if (!hasSecondaries) {
            var minute = 0f
            while (minute < 1440f) {
                minute = (minute + 16f).coerceAtMost(1440f)
                clockSpinPhase = minute / 1440f
                delay(16L)
            }
        }
    }

    // Full stats — debounced. While waiting, show "..." for streak/anti-streak.
    var dayStats by remember { mutableStateOf(lightStats) }
    var statsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedDate) {
        // Reset to light stats immediately (shows "..." for streak/anti-streak)
        dayStats = lightStats
        statsLoading = true
        // Wait 400ms — cancelled if selectedDate changes again (rapid playback)
        delay(400L)
        dayStats = viewModel.getDayStats(selectedDate)
        statsLoading = false
    }

    // ── Country counts derived from the in-memory cache (fast O(N) scan) ────
    val countriesVisited = remember(selectedDate, countryTimeline) {
        val seen = HashSet<String>()
        for ((d, c) in countryTimeline) {
            if (d.isAfter(selectedDate)) break  // timeline is sorted ascending
            seen.add(c)
        }
        seen.size
    }
    // Cached PRIMARY location label for the top bar (avoid SharedPrefs on
    // every recomp). Always show a label: use the exact entry for the day
    // if present, otherwise fall back to the most recent preceding entry
    // and mark it as assumed (*).
    val primaryLabelPair = remember(selectedDate, locationVersion) {
        val exact = viewModel.getLocationLabelForDate(selectedDate)
        if (exact != null) {
            exact to false
        } else {
            // Walk backwards through stored labels to find the last known one.
            val allLabels = viewModel.getAllStoredLabels()
            val lastKnown = allLabels.entries
                .mapNotNull { (k, v) ->
                    runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.let { it to v }
                }
                .filter { (d, _) -> d.isBefore(selectedDate) }
                .maxByOrNull { (d, _) -> d }
                ?.second
            (lastKnown ?: allLabels.values.firstOrNull()) to true
        }
    }
    // When the user has stepped to a distinct secondary, show that
    // secondary's label (produced via the same pipeline as the primary —
    // see LocationRepository.deriveSecondaryLabel). It will naturally
    // collapse back to the primary's label if the labeling method yields
    // the same string for both coordinates.
    val activeSecondary = secondaryStepIndex?.let { idx ->
        distinctDaySecondaries.getOrNull(idx)
    }
    val locationLabel = activeSecondary?.label ?: primaryLabelPair.first
    val locationIsAssumed = activeSecondary == null && primaryLabelPair.second

    // ── Location timeline popup state ──────────────────────────────────────
    var showLocationTimeline by remember { mutableStateOf(false) }

    // ── Layout ──────────────────────────────────────────────────────────────
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0A0A)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MapTopBar(
                locationLabel = locationLabel,
                isAssumed = locationIsAssumed,
                onClick = { showLocationTimeline = true },
                onSettingsClick = { showAddLocationDialog = true }
            )

            if (showLocationTimeline) {
                val timelineEntries = remember(locationVersion) {
                    viewModel.getAllStoredLabels().entries
                        .mapNotNull { (dateStr, label) ->
                            runCatching { LocalDate.parse(dateStr) to label }.getOrNull()
                        }
                        .sortedBy { it.first }
                }
                LocationTimelinePopup(
                    entries = timelineEntries,
                    selectedDate = selectedDate,
                    accent = accent,
                    onNavigate = { date ->
                        isPlaying = false
                        viewModel.navigateToDate(date)
                        showLocationTimeline = false
                    },
                    onDismiss = { showLocationTimeline = false },
                    onGetCoords = { date -> viewModel.getCoordsForDate(date) },
                    onSetCoords = { date, lat, lon -> viewModel.setCoordsForDate(date, lat, lon) }
                )
            }

            if (showAddLocationDialog) {
                AddSecondaryLocationDialog(
                    date = selectedDate,
                    accent = accent,
                    showAll = showAll,
                    onShowAllChange = { showAll = it },
                    onAdd = { address, timeMinutes ->
                        scope.launch {
                            viewModel.addManualSecondaryLocation(selectedDate, address, timeMinutes)
                        }
                        showAddLocationDialog = false
                    },
                    onDismiss = { showAddLocationDialog = false }
                )
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // ── Map area (left, takes remaining width) ─────────────────
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    WorldMapWithMarker(
                        currentCoords = currentDisplayCoords,
                        allCoordsTrail = coordsByDate,
                        selectedDate = selectedDate,
                        dotColorsByDate = dotColorsByDate,
                        secondaryByDate = if (showAll) secondaryByDate else emptyMap(),
                        accent = accent,
                        speed = speed,
                        onSizeChanged = { mapSize = it }
                    )

                    // ── Clock overlay (bottom-left of map) ──────────────────
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        DayClock(
                            timeMinutes = clockTimeMinutes,
                            spinPhase = clockSpinPhase,
                            accent = accent
                        )
                    }
                }
                // ── Side info panel (right) ────────────────────────────────
                MapInfoPanel(
                    date = selectedDate,
                    stats = dayStats,
                    statsLoading = statsLoading,
                    countriesVisited = countriesVisited,
                    onCountriesClick = {
                        // Build sorted distinct list from the cache only when the popup opens
                        val seen = LinkedHashSet<String>()
                        for ((d, c) in countryTimeline) {
                            if (d.isAfter(selectedDate)) break
                            seen.add(c)
                        }
                        seen.toList().sorted()
                    },
                    onPointsClick = { viewModel.getDayHabitBreakdown(selectedDate) },
                    onGetIgnoredCountries = { viewModel.getIgnoredCountryNames() },
                    onAddIgnoredCountry = { name -> viewModel.addIgnoredCountryName(name) },
                    onRemoveIgnoredCountry = { name -> viewModel.removeIgnoredCountryName(name) },
                    accent = accent,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(220.dp)
                )
            }

            // ── Timeline + transport controls ──────────────────────────────
            TimelineBar(
                firstDate = firstDate,
                lastDate = lastDate,
                totalDays = totalDays,
                selectedDate = selectedDate,
                clockTimeMinutes = clockTimeMinutes,
                // Decimal in the "N / M" label is only shown when the user
                // is actively viewing a secondary for this day (Bug B fix).
                // When secondaryStepIndex == null, the WHOLE-NUMBER primary
                // is shown — matching the legacy behaviour.
                dayHasSecondaries = showAll && secondaryStepIndex != null &&
                    secondaryStepIndex!! in distinctDaySecondaries.indices,
                onScrub = { newDate ->
                    isPlaying = false
                    // Slider scrub always returns to the day's primary view.
                    secondaryStepIndex = null
                    viewModel.navigateToDate(newDate)
                },
                onStepDay = { delta ->
                    isPlaying = false
                    // Walk secondaries within the current day first, then
                    // cross to the adjacent day's primary (Bug D fix).
                    // State machine (forward / delta = +1):
                    //   primary (null) → secondary 0 → secondary 1 → ... →
                    //   secondary N-1 → next day primary (null).
                    // Reverse for delta = -1.
                    val n = distinctDaySecondaries.size
                    val curIdx = secondaryStepIndex
                    if (delta > 0) {
                        when {
                            // Currently on primary: jump to first secondary if any.
                            curIdx == null && n > 0 -> secondaryStepIndex = 0
                            // Currently on a secondary, more remain: advance.
                            curIdx != null && curIdx < n - 1 -> secondaryStepIndex = curIdx + 1
                            // No more secondaries for today: move to next day's primary.
                            else -> {
                                val target = selectedDate.plusDays(1)
                                val clamped = if (target.isAfter(lastDate)) lastDate else target
                                if (clamped != selectedDate) viewModel.navigateToDate(clamped)
                            }
                        }
                    } else {
                        when {
                            // Currently on secondary 0: back to primary.
                            curIdx == 0 -> secondaryStepIndex = null
                            // Currently on a later secondary: step back one.
                            curIdx != null && curIdx > 0 -> secondaryStepIndex = curIdx - 1
                            // Currently on primary: cross to previous day's
                            // LAST distinct secondary (or primary if none).
                            else -> {
                                val target = selectedDate.minusDays(1)
                                val clamped = if (target.isBefore(firstDate)) firstDate else target
                                if (clamped != selectedDate) {
                                    viewModel.navigateToDate(clamped)
                                    val prevSecs = secondaryByDate[clamped]
                                        ?.sortedBy { it.timeMinutes }.orEmpty()
                                    val prevPrimary = coordsByDate[clamped]
                                    val prevDistinct = run {
                                        val kept = mutableListOf<SecondaryLocation>()
                                        for (sec in prevSecs) {
                                            val tooClosePrimary = prevPrimary != null &&
                                                com.example.tail.data.haversineMeters(
                                                    prevPrimary.first, prevPrimary.second,
                                                    sec.lat, sec.lon
                                                ) < 250.0
                                            if (tooClosePrimary) continue
                                            val tooCloseKept = kept.any {
                                                com.example.tail.data.haversineMeters(
                                                    it.lat, it.lon, sec.lat, sec.lon
                                                ) < 250.0
                                            }
                                            if (tooCloseKept) continue
                                            kept.add(sec)
                                        }
                                        kept
                                    }
                                    // LaunchedEffect(selectedDate) will set
                                    // secondaryStepIndex = pendingStepIndexOnDateChange
                                    // after the navigation recomposes. We park the
                                    // desired "last distinct secondary" here so it
                                    // survives the date-change handler.
                                    pendingStepIndexOnDateChange =
                                        if (prevDistinct.isNotEmpty()) prevDistinct.size - 1
                                        else null
                                }
                            }
                        }
                    }
                },
                isPlaying = isPlaying,
                onTogglePlay = { isPlaying = !isPlaying },
                speed = speed,
                onSpeedChange = { newIndex -> speedIndex = newIndex },
                accent = accent
            )
        }
    }
}

// ── Day → accent colour mapping ─────────────────────────────────────────────
// Maps daily total points onto the 7-tier Border* palette (vivid versions of
// the habit colours) so the accent reads clearly against the dark map background.
// Thresholds: <14 red · 14-20 orange · 21-30 green · 31-41 blue ·
//             42-48 pink · 49-55 yellow · 56+ white
private fun accentColorForPoints(points: Int): Color = when {
    points >= 56 -> BorderGlass    // white
    points >= 49 -> BorderYellow
    points >= 42 -> BorderPink
    points >= 31 -> BorderBlue
    points >= 21 -> BorderGreen
    points >= 14 -> BorderOrange
    else         -> BorderRed
}

/** Slightly darker variant of [c], used for slider active track. */
private fun Color.darker(factor: Float = 0.75f): Color =
    Color(
        red   = (red   * factor).coerceIn(0f, 1f),
        green = (green * factor).coerceIn(0f, 1f),
        blue  = (blue  * factor).coerceIn(0f, 1f),
        alpha = alpha
    )

/** Translucent halo colour derived from [c]. */
private fun Color.halo(alpha: Float = 0.20f): Color =
    Color(red = red, green = green, blue = blue, alpha = alpha)

// ── Top bar: location name aligned to the right of the map area ─────────────
//
// No back arrow — system back handles navigation. The location label is
// aligned to the right side of the world map (which takes up the space
// minus the 220dp info panel).

@Composable
private fun MapTopBar(
    locationLabel: String?,
    isAssumed: Boolean,
    onClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Always show something — if no label at all, reserve space so layout
        // doesn't jump. The " *" suffix is always reserved in layout but rendered
        // transparent when not assumed, so the label itself never shifts position.
        val labelText = locationLabel ?: " "
        val suffixColor = when {
            locationLabel == null -> Color.Transparent
            isAssumed             -> Color(0xFFCCCCCC)
            else                  -> Color.Transparent
        }
        val display = buildAnnotatedString {
            withStyle(SpanStyle(color = if (locationLabel != null) Color(0xFFCCCCCC) else Color.Transparent)) {
                append(labelText)
            }
            withStyle(SpanStyle(color = suffixColor)) {
                append(" *")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Spacer to push the text to the right edge of the map area.
            // The map area takes weight(1f) and the info panel takes 220.dp.
            // So we want the text to be aligned to the end of the weight(1f) section.
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = display,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick
                    )
            )
            // Plus button — directly to the right of the location label
            Text(
                text = "+",
                color = Color(0xFF888888),
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onSettingsClick
                    )
                    .padding(start = 8.dp)
            )
            // Reserve space for the 220dp info panel on the right
            Spacer(modifier = Modifier.width(220.dp))
        }
    }
}

// ── Map background + marker ─────────────────────────────────────────────────

@Composable
private fun WorldMapWithMarker(
    currentCoords: Pair<Double, Double>?,
    allCoordsTrail: Map<LocalDate, Pair<Double, Double>>,
    selectedDate: LocalDate,
    dotColorsByDate: Map<LocalDate, Color>,
    secondaryByDate: Map<LocalDate, List<SecondaryLocation>>,
    accent: Color,
    speed: Float = 2f,
    onSizeChanged: (Size) -> Unit = {}
) {
    val effectiveSpeed = if (speed == -1f) 240f else speed
    val context = LocalContext.current
    // Load world polygons OFF the main thread so the screen doesn't freeze
    // while the asset is parsed. Empty list until ready (just shows the dark
    // background + dots, which is fine).
    var land by remember { mutableStateOf<List<List<Pair<Double, Double>>>>(emptyList()) }
    LaunchedEffect(Unit) {
        land = withContext(Dispatchers.Default) { WorldLandData.load(context) }
    }

    // Animate the marker between coord changes for a smooth slide.
    // The animation duration is computed per-jump from the actual distance,
    // capped so the marker never moves faster than MAX_MARKER_SPEED_FRAC_PER_SEC
    // — that's why long jumps look smooth (the playback loop also waits the
    // same duration before advancing the date).
    val animX = remember { Animatable(0f) }
    val animY = remember { Animatable(0f) }
    var lastSize by remember { mutableStateOf(Size.Zero) }
    var hasInitialPosition by remember { mutableStateOf(false) }

    // Pinch-to-zoom state
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(currentCoords, lastSize) {
        if (currentCoords == null || lastSize == Size.Zero) return@LaunchedEffect
        val (lat, lon) = currentCoords
        val tx = lonToX(lon, lastSize.width)
        val ty = latToY(lat, lastSize.height)
        if (!hasInitialPosition) {
            // Snap (don't animate) on first measure so the marker doesn't crawl
            // in from the corner.
            animX.snapTo(tx)
            animY.snapTo(ty)
            hasInitialPosition = true
        } else {
            // Distance-based animation duration. The speed-cap (max pixels/sec
            // = MAX_MARKER_SPEED_FRAC_PER_SEC * mapWidth) means a continent-
            // crossing jump takes longer than a same-city day, even at high
            // playback speeds. The day-tick window (msPerDay) is the lower
            // bound — we never animate slower than the user's chosen speed.
            // World wrapping: if the shortest path crosses the map edge,
            // animate through the virtual off-screen coordinate.
            var dx = tx - animX.value
            if (dx > lastSize.width / 2f) dx -= lastSize.width
            if (dx < -lastSize.width / 2f) dx += lastSize.width
            val virtualTx = animX.value + dx
            val dy = ty - animY.value
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val msPerDay = (1000f / effectiveSpeed).toLong().coerceAtLeast(15L)
            val travelMs = requiredAnimMs(dist, lastSize.width)
            val durMs = kotlin.math.max(MIN_MARKER_ANIM_MS, kotlin.math.max(msPerDay, travelMs))
                .coerceAtMost(2000L)
                .toInt()
            // Animate X and Y in PARALLEL so the marker moves diagonally in a
            // straight line between two days. After X animation, snap back to
            // screen-space so the next animation starts from a normalised pos.
            launch {
                animX.animateTo(virtualTx, animationSpec = tween(durMs))
                animX.snapTo(tx)
            }
            launch { animY.animateTo(ty, animationSpec = tween(durMs)) }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF080808))
            .pointerInput(Unit) {
                var lastTapUpTime = 0L

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTime = firstDown.uptimeMillis

                    var isTransform = false
                    var totalMovement = 0f
                    var prevCentroid = firstDown.position
                    var prevPinchDist = 0f
                    val pointerPositions = mutableMapOf(firstDown.id to firstDown.position)

                    while (pointerPositions.isNotEmpty()) {
                        val event = awaitPointerEvent()

                        // Update pointer tracking
                        for (change in event.changes) {
                            if (change.pressed) {
                                pointerPositions[change.id] = change.position
                            } else {
                                pointerPositions.remove(change.id)
                            }
                        }

                        if (pointerPositions.size >= 2) {
                            isTransform = true
                            val positions = pointerPositions.values.toList()
                            val centroid = Offset(
                                positions.map { it.x }.average().toFloat(),
                                positions.map { it.y }.average().toFloat()
                            )
                            val dx = positions[0].x - positions[1].x
                            val dy = positions[0].y - positions[1].y
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                            if (prevPinchDist > 0f) {
                                val zoom = dist / prevPinchDist
                                val newScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                if (newScale > 1f) {
                                    val scaleRatio = newScale / zoomScale
                                    val pan = Offset(
                                        centroid.x - prevCentroid.x,
                                        centroid.y - prevCentroid.y
                                    )
                                    zoomOffset = Offset(
                                        centroid.x - (centroid.x - zoomOffset.x) * scaleRatio + pan.x,
                                        centroid.y - (centroid.y - zoomOffset.y) * scaleRatio + pan.y
                                    )
                                    zoomScale = newScale
                                } else {
                                    zoomScale = 1f
                                    zoomOffset = Offset.Zero
                                }
                            }
                            prevCentroid = centroid
                            prevPinchDist = dist
                        } else if (pointerPositions.size == 1) {
                            val pos = pointerPositions.values.first()

                            // When transitioning from pinch (2 fingers) to single finger,
                            // skip the pan delta to avoid a snap — just update the reference point.
                            if (prevPinchDist > 0f) {
                                prevCentroid = pos
                                prevPinchDist = 0f
                            } else {
                                val dx = pos.x - prevCentroid.x
                                val dy = pos.y - prevCentroid.y
                                val move = kotlin.math.sqrt(dx * dx + dy * dy)
                                totalMovement += move

                                if (zoomScale > 1f && totalMovement > 8f) {
                                    isTransform = true
                                    zoomOffset = Offset(zoomOffset.x + dx, zoomOffset.y + dy)
                                }
                                prevCentroid = pos
                            }
                        }

                        event.changes.forEach { it.consume() }
                    }

                    // Tap detection: not a transform, short duration, small movement
                    val upTime = System.currentTimeMillis()
                    val isTap = !isTransform && (upTime - downTime) < 300 && totalMovement < 20f

                    if (isTap) {
                        if (upTime - lastTapUpTime < 300) {
                            // Double tap → reset zoom
                            zoomScale = 1f
                            zoomOffset = Offset.Zero
                            lastTapUpTime = 0L
                        } else {
                            lastTapUpTime = upTime
                        }
                    } else {
                        lastTapUpTime = 0L
                    }
                }
            }
            .onSizeChanged { newSize ->
                val s = Size(newSize.width.toFloat(), newSize.height.toFloat())
                lastSize = s
                onSizeChanged(s)
            }
    ) {
        drawContext.canvas.save()
        drawContext.canvas.translate(zoomOffset.x, zoomOffset.y)
        drawContext.canvas.scale(zoomScale, zoomScale)

        // Continent fills.
        val landColor = Color(0xFF2A2A2A)
        val landStroke = Color(0xFF444444)
        for (ring in land) {
            if (ring.size < 3) continue
            val path = Path()
            val first = ring[0]
            path.moveTo(lonToX(first.first, size.width), latToY(first.second, size.height))
            for (i in 1 until ring.size) {
                val (lon, lat) = ring[i]
                path.lineTo(lonToX(lon, size.width), latToY(lat, size.height))
            }
            path.close()
            drawPath(path, color = landColor)
            drawPath(path, color = landStroke, style = Stroke(width = 0.6f))
        }

        // Equator + prime meridian — faint guides.
        val gridColor = Color(0xFF1A1A1A)
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 0.5f
        )
        drawLine(
            color = gridColor,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = 0.5f
        )

        // Trail of visited dots — only show dots for days up to the selected
        // date, so they appear progressively as the timeline advances.
        // Each dot is locked to the accent colour of its own day.
        for ((date, coord) in allCoordsTrail) {
            if (date.isAfter(selectedDate)) continue
            val (lat, lon) = coord
            val dotColor = (dotColorsByDate[date] ?: accent).copy(alpha = 0.55f)
            drawCircle(
                color = dotColor,
                radius = 2.0f,
                center = Offset(lonToX(lon, size.width), latToY(lat, size.height))
            )
        }

        // Secondary location dots — smaller, slightly more transparent dots
        // for positions logged each time the app was opened throughout the day.
        // Only shown for days up to the selected date.
        for ((date, secondaries) in secondaryByDate) {
            if (date.isAfter(selectedDate)) continue
            val secColor = (dotColorsByDate[date] ?: accent).copy(alpha = 0.35f)
            for (sec in secondaries) {
                drawCircle(
                    color = secColor,
                    radius = 1.3f,
                    center = Offset(lonToX(sec.lon, size.width), latToY(sec.lat, size.height))
                )
            }
        }

        // Current-day marker — a stylised "person pin" (head + body), tinted
        // by the day's accent colour. Wrap X for world-circling travel.
        if (currentCoords != null) {
            val cx = ((animX.value % size.width) + size.width) % size.width
            val cy = animY.value
            // Lighter "head" tone — blend accent → near-white.
            val headColor = Color(
                red = (accent.red + (1f - accent.red) * 0.45f).coerceIn(0f, 1f),
                green = (accent.green + (1f - accent.green) * 0.45f).coerceIn(0f, 1f),
                blue = (accent.blue + (1f - accent.blue) * 0.45f).coerceIn(0f, 1f),
                alpha = 1f
            )
            // Halo
            drawCircle(color = accent.halo(0.20f), radius = 22f, center = Offset(cx, cy))
            // Body
            drawCircle(color = accent, radius = 8.5f, center = Offset(cx, cy + 6f))
            // Head
            drawCircle(color = headColor, radius = 6.5f, center = Offset(cx, cy - 7f))
            // Outline
            drawCircle(
                color = Color(0xFF111111),
                radius = 8.5f,
                center = Offset(cx, cy + 6f),
                style = Stroke(width = 1.5f)
            )
        }

        drawContext.canvas.restore()
    }
}

/** Equirectangular projection: lon ∈ [-180, 180] → [0, width]. */
private fun lonToX(lon: Double, width: Float): Float =
    ((lon + 180.0) / 360.0 * width).toFloat()

/** Equirectangular projection: lat ∈ [90, -90] → [0, height]. */
private fun latToY(lat: Double, height: Float): Float =
    ((90.0 - lat) / 180.0 * height).toFloat()

// ── Side info panel ─────────────────────────────────────────────────────────

@Composable
private fun MapInfoPanel(
    date: LocalDate,
    stats: DayStats,
    statsLoading: Boolean,
    countriesVisited: Int,
    onCountriesClick: () -> List<String>,
    onPointsClick: () -> List<Pair<String, Int>>,
    onGetIgnoredCountries: () -> Set<String>,
    onAddIgnoredCountry: (String) -> Unit,
    onRemoveIgnoredCountry: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    var showCountriesPopup by remember { mutableStateOf(false) }
    var showIgnoredDialog  by remember { mutableStateOf(false) }
    var showPointsPopup    by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(Color(0xFF0E0E0E))
            .padding(12.dp)
    ) {
        // Date header — moved here from the top bar so the centred location
        // label can dominate the screen header.
        Text(
            text = date.format(MAP_DATE_FMT),
            color = Color.White,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(10.dp))

        ClickableStatLine(
            label = "Day points",
            value = stats.totalPoints.toString(),
            accent = accent,
            onClick = { showPointsPopup = true }
        )
        StatLine(
            "Monthly avg",
            String.format("%.1f", stats.monthlyAverage),
            accent
        )
        StatLine("Streak",      if (statsLoading) "..." else "${stats.streakDays} d",     accent)
        StatLine("Anti-streak", if (statsLoading) "..." else "${stats.antiStreakDays} d", accent)
        ClickableStatLine(
            label = "Countries",
            value = countriesVisited.toString(),
            accent = accent,
            onClick = { showCountriesPopup = true }
        )
    }

    if (showCountriesPopup) {
        val countries = remember { onCountriesClick() }
        SimpleListPopup(
            title = "Countries visited",
            items = countries,
            accent = accent,
            onDismiss = { showCountriesPopup = false },
            onEditIgnored = { showIgnoredDialog = true }
        )
    }

    if (showIgnoredDialog) {
        IgnoredCountriesDialog(
            ignoredNames = remember(showIgnoredDialog) { onGetIgnoredCountries().sorted() },
            accent = accent,
            onAdd = onAddIgnoredCountry,
            onRemove = onRemoveIgnoredCountry,
            onDismiss = { showIgnoredDialog = false }
        )
    }

    if (showPointsPopup) {
        val breakdown = remember { onPointsClick() }
        HabitBreakdownPopup(
            items = breakdown,
            accent = accent,
            onDismiss = { showPointsPopup = false }
        )
    }
}

@Composable
private fun StatLine(label: String, value: String, accent: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(text = value, color = accent, fontSize = 14.sp)
    }
}

/** A stat row whose value text is tappable (underlined hint via colour). */
@Composable
private fun ClickableStatLine(label: String, value: String, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
        )
    }
}

// ── Popup: scrollable list of strings ───────────────────────────────────────

@Composable
private fun SimpleListPopup(
    title: String,
    items: List<String>,
    accent: Color,
    onDismiss: () -> Unit,
    onEditIgnored: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = accent, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (onEditIgnored != null) {
                    TextButton(onClick = onEditIgnored) {
                        Text("Edit", color = Color(0xFF999999), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF222222))
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items) { item ->
                    Text(
                        text = item,
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Popup: habit breakdown (name + points) ───────────────────────────────────

@Composable
private fun HabitBreakdownPopup(
    items: List<Pair<String, Int>>,
    accent: Color,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Habits today", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF222222))
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items) { (name, pts) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, color = Color(0xFFCCCCCC), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(pts.toString(), color = accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ── Popup: location timeline (all date → location pairs) ────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocationTimelinePopup(
    entries: List<Pair<LocalDate, String>>,
    selectedDate: LocalDate,
    accent: Color,
    onNavigate: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onGetCoords: (LocalDate) -> Pair<Double, Double>?,
    onSetCoords: (LocalDate, Double, Double) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = entries.indexOfFirst { it.first == selectedDate }
            .coerceAtLeast(0)
    )

    // Track which date's coordinates are being edited via long-press
    var editingCoordsDate by remember { mutableStateOf<LocalDate?>(null) }
    var coordsText by remember { mutableStateOf("") }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 700.dp)
                .fillMaxHeight(0.75f)
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Location Timeline", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF222222))
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(entries) { (date, label) ->
                    val isSelected = date == selectedDate
                    val isEditingCoords = editingCoordsDate == date

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onNavigate(date) },
                                    onLongClick = {
                                        val coords = onGetCoords(date)
                                        coordsText = if (coords != null) {
                                            "${coords.first}, ${coords.second}"
                                        } else ""
                                        editingCoordsDate = date
                                    }
                                )
                                .padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = date.format(MAP_DATE_FMT),
                                color = if (isSelected) accent else Color(0xFF888888),
                                fontSize = 12.sp,
                                modifier = Modifier.width(140.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else Color(0xFFCCCCCC),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Editable coordinates field — shown when long-pressed
                        if (isEditingCoords) {
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 152.dp, top = 2.dp, bottom = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = coordsText,
                                    onValueChange = { coordsText = it },
                                    placeholder = {
                                        Text("lat,lon", fontSize = 12.sp, color = Color(0xFF555555))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accent,
                                        unfocusedBorderColor = Color(0xFF333333),
                                        focusedTextColor = Color(0xFFCCCCCC),
                                        unfocusedTextColor = Color(0xFFCCCCCC),
                                        cursorColor = accent
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val trimmed = coordsText.trim()
                                        if (trimmed.isEmpty()) {
                                            editingCoordsDate = null
                                        } else {
                                            val parsed = parseCoordsInput(trimmed)
                                            if (parsed != null) {
                                                onSetCoords(date, parsed.first, parsed.second)
                                                editingCoordsDate = null
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Invalid coordinates. Use format: lat,lon",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    })
                                )
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = {
                                    clipboardManager.setText(
                                        androidx.compose.ui.text.AnnotatedString(coordsText)
                                    )
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }, enabled = coordsText.isNotBlank()) {
                                    Text("Copy", color = if (coordsText.isNotBlank()) accent else Color(0xFF444444), fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    val pasted = clipboardManager.getText()?.text ?: ""
                                    if (pasted.isNotBlank()) {
                                        val parsed = parseCoordsInput(pasted.trim())
                                        if (parsed != null) {
                                            coordsText = "$pasted"
                                            onSetCoords(date, parsed.first, parsed.second)
                                            editingCoordsDate = null
                                        } else {
                                            coordsText = pasted
                                            Toast.makeText(
                                                context,
                                                "Invalid coordinates. Use format: lat,lon",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }) {
                                    Text("Paste", color = Color(0xFF999999), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Parses a "lat,lon" string. Returns (lat, lon) or null if invalid. */
private fun parseCoordsInput(input: String): Pair<Double, Double>? {
    val parts = input.split(",").map { it.trim() }
    if (parts.size != 2) return null
    return try {
        val lat = parts[0].toDouble()
        val lon = parts[1].toDouble()
        if (lat in -90.0..90.0 && lon in -180.0..180.0) Pair(lat, lon) else null
    } catch (_: NumberFormatException) {
        null
    }
}

// ── Ignored-country editor dialog ───────────────────────────────────────────

/**
 * Dialog that shows the current ignored-country list and lets the user
 * add new entries or remove existing ones.
 */
@Composable
private fun IgnoredCountriesDialog(
    ignoredNames: List<String>,
    accent: Color,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    // Local mutable copy so removals feel instant without waiting for a
    // recomposition triggered by the ViewModel version bump.
    var localList by remember(ignoredNames) { mutableStateOf(ignoredNames) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Ignored country names", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Names in this list are excluded from the countries count.",
                color = Color(0xFF777777),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF222222))
            Spacer(Modifier.height(6.dp))

            // ── Add row ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Add name…", fontSize = 12.sp, color = Color(0xFF555555)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = accent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty() && trimmed !in localList) {
                            onAdd(trimmed)
                            localList = (localList + trimmed).sorted()
                        }
                        inputText = ""
                    })
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val trimmed = inputText.trim()
                    if (trimmed.isNotEmpty() && trimmed !in localList) {
                        onAdd(trimmed)
                        localList = (localList + trimmed).sorted()
                    }
                    inputText = ""
                }) {
                    Text("Add", color = accent, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── List of current ignored names ─────────────────────────────
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(localList) { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = name,
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            onRemove(name)
                            localList = localList - name
                        }) {
                            Text("✕", color = Color(0xFF666666), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Timeline + transport controls ───────────────────────────────────────────

@Composable
private fun TimelineBar(
    firstDate: LocalDate,
    lastDate: LocalDate,
    totalDays: Int,
    selectedDate: LocalDate,
    onScrub: (LocalDate) -> Unit,
    onStepDay: (Int) -> Unit,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    speed: Float,
    onSpeedChange: (Int) -> Unit,
    accent: Color,
    clockTimeMinutes: Int? = null,
    dayHasSecondaries: Boolean = false
) {
    var showSpeedDropdown by remember { mutableStateOf(false) }

    val daysFromStart = java.time.temporal.ChronoUnit.DAYS
        .between(firstDate, selectedDate)
        .coerceIn(0L, totalDays.toLong())
        .toInt()

    // For days with secondary locations, add a decimal based on time of day.
    // Noon = .50, midnight = .00, etc. Precision to .01 (≈14.4 min).
    val daysDisplay = if (dayHasSecondaries && clockTimeMinutes != null) {
        val fraction = (clockTimeMinutes / 1440.0 * 100).roundToInt() / 100.0
        String.format("%.2f", daysFromStart + fraction)
    } else {
        daysFromStart.toString()
    }
    val canStepBack = selectedDate.isAfter(firstDate)
    val canStepForward = selectedDate.isBefore(lastDate)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play / pause
            IconButton(onClick = onTogglePlay) {
                if (isPlaying) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause", tint = accent)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accent)
                }
            }
            Spacer(Modifier.width(6.dp))
            
            Box {
                Text(
                    text = if (speed == -1f) "Auto" else "${if (speed == speed.toLong().toFloat()) speed.toLong() else speed}×",
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { showSpeedDropdown = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                
                androidx.compose.material3.DropdownMenu(
                    expanded = showSpeedDropdown,
                    onDismissRequest = { showSpeedDropdown = false },
                    modifier = Modifier.background(Color(0xFF1A1A1A))
                ) {
                    PLAY_SPEEDS.forEachIndexed { index, s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (s == -1f) "Auto" else "${if (s == s.toLong().toFloat()) s.toLong() else s}×",
                                    color = if (s == speed) accent else Color.White
                                )
                            },
                            onClick = {
                                onSpeedChange(index)
                                showSpeedDropdown = false
                            }
                        )
                    }
                }
            }
            
            Spacer(Modifier.width(8.dp))
            // Slider takes the rest of the row
            Slider(
                value = daysFromStart.toFloat(),
                valueRange = 0f..totalDays.coerceAtLeast(1).toFloat(),
                onValueChange = { v ->
                    val newDate = firstDate.plusDays(v.toLong())
                    onScrub(newDate)
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent.darker(0.8f),
                    inactiveTrackColor = Color(0xFF333333)
                )
            )
            Spacer(Modifier.width(4.dp))
            // ── Step-by-day controls (sit right next to the day counter) ──
            RepeatIconButton(
                onClick = { if (canStepBack) onStepDay(-1) },
                enabled = canStepBack
            ) {
                Text(
                    text = "‹",
                    color = if (canStepBack) Color.White else Color(0xFF444444),
                    fontSize = 22.sp
                )
            }
            Text(
                text = "$daysDisplay / $totalDays",
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp
            )
            RepeatIconButton(
                onClick = { if (canStepForward) onStepDay(1) },
                enabled = canStepForward
            ) {
                Text(
                    text = "›",
                    color = if (canStepForward) Color.White else Color(0xFF444444),
                    fontSize = 22.sp
                )
            }
        }
    }
}

// ── Add secondary location dialog ──────────────────────────────────────────

/**
 * Dialog for manually adding a secondary location by pasting an address
 * (e.g. from Google Maps). The address is forward-geocoded to get coords.
 * Also allows setting the time of day for the visit via swipe wheel pickers.
 * Includes a toggle for showing all locations on the map.
 */
@Composable
private fun AddSecondaryLocationDialog(
    date: LocalDate,
    accent: Color,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onAdd: (address: String, timeMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var addressText by remember { mutableStateOf("") }
    val now = java.time.LocalTime.now()
    var selectedHour by remember { mutableStateOf(now.hour) }
    var selectedMinute by remember { mutableStateOf(now.minute) }

    val hours = (0..23).map { String.format("%02d", it) }
    val minutes = (0..59).map { String.format("%02d", it) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Add location for ${date.format(MAP_DATE_FMT)}", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a Google Maps address or place name. It will be geocoded to coordinates.",
                color = Color(0xFF777777),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF222222))
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                placeholder = { Text("Address or place name…", fontSize = 12.sp, color = Color(0xFF555555)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = accent
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )

            Spacer(Modifier.height(12.dp))

            // Show all locations toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show all locations", color = Color(0xFFCCCCCC), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = showAll,
                    onCheckedChange = onShowAllChange,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = accent,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF333333),
                        uncheckedThumbColor = Color(0xFF888888)
                    )
                )
            }

            Spacer(Modifier.height(12.dp))

            Text("Time of visit:", color = Color(0xFF888888), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            // 24-hour wheel pickers for hours and minutes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelPicker(
                    items = hours,
                    selectedIndex = selectedHour,
                    onSelectedChange = { selectedHour = it },
                    itemHeight = 36.dp,
                    visibleItems = 5,
                    accent = accent,
                    modifier = Modifier.width(80.dp)
                )
                Text(":", color = Color(0xFF888888), fontSize = 24.sp, modifier = Modifier.padding(horizontal = 8.dp))
                WheelPicker(
                    items = minutes,
                    selectedIndex = selectedMinute,
                    onSelectedChange = { selectedMinute = it },
                    itemHeight = 36.dp,
                    visibleItems = 5,
                    accent = accent,
                    modifier = Modifier.width(80.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888), fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val timeMinutes = selectedHour * 60 + selectedMinute
                        onAdd(addressText.trim(), timeMinutes)
                    },
                    enabled = addressText.isNotBlank()
                ) {
                    Text("Add", color = if (addressText.isNotBlank()) accent else Color(0xFF444444), fontSize = 13.sp)
                }
            }
        }
    }
}

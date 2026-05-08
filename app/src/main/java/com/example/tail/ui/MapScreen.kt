package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.DayStats
import com.example.tail.ui.map.WorldLandData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Display formatting for the map screen.
private val MAP_DATE_FMT = DateTimeFormatter.ofPattern("yyyy, MMM dd, EEE")

// Available playback speeds in days/sec. -1f represents "Auto" mode.
private val PLAY_SPEEDS = listOf(0.5f, 1f, 2f, 5f, 15f, 30f, 60f, 120f, -1f)
private const val DEFAULT_SPEED_INDEX = 2  // 2 days/sec

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
        onDispose {
            // Restore whatever orientation policy was in effect before we entered.
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
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
    val locationVersion = viewModel.locationDataVersion
    LaunchedEffect(locationVersion) {
        val (coords, countries) = withContext(Dispatchers.Default) {
            // Single SharedPrefs read + single JSON parse → O(N) instead of
            // O(N²) date-by-date lookups.
            val c = viewModel.getAllStoredCoordsParsed()
            val ct = viewModel.buildCountryTimeline()
            c to ct
        }
        coordsByDate = coords
        countryTimeline = countries
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
    val currentDisplayCoords = remember(selectedDate, coordsByDate) {
        coordsByDate[selectedDate]
            ?: coordsByDate.entries
                .filter { it.key.isBefore(selectedDate) }
                .maxByOrNull { it.key }
                ?.value
            ?: coordsByDate.entries.minByOrNull { it.key }?.value
    }

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
        
        val isAuto = speed == -1f
        val effectiveSpeed = if (isAuto) 240f else speed
        // Nominal time per day at the current speed.
        val msPerDay = (1000f / effectiveSpeed).toLong().coerceAtLeast(15L)
        
        while (isPlaying) {
            val cur = viewModel.selectedDate.value
            if (!cur.isBefore(lastDate)) {
                isPlaying = false
                break
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
                val dx = lonToX(lon2, mapSize.width) - lonToX(lon1, mapSize.width)
                val dy = latToY(lat2, mapSize.height) - latToY(lat1, mapSize.height)
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                requiredAnimMs(dist, mapSize.width)
            } else 0L
            
            // Check for location change in Auto mode
            var autoPauseMs = 0L
            if (isAuto) {
                val curLabel = viewModel.getLocationLabelForDate(cur)
                val nextLabel = viewModel.getLocationLabelForDate(nextDate)
                
                // Only pause if the next day has an explicit label AND it's different from the current day's explicit label.
                // If nextLabel is null, it means it's an assumed location, so we don't pause.
                // If curLabel is null, we only pause if nextLabel is a new explicit location we haven't seen just before.
                // To be robust, let's get the effective labels for both days.
                
                val allLabels = viewModel.getAllStoredLabels()
                
                val effectiveCurLabel = curLabel ?: allLabels.entries
                    .mapNotNull { (k, v) -> runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.let { it to v } }
                    .filter { (d, _) -> d.isBefore(cur) }
                    .maxByOrNull { (d, _) -> d }?.second ?: allLabels.values.firstOrNull()
                    
                val effectiveNextLabel = nextLabel ?: allLabels.entries
                    .mapNotNull { (k, v) -> runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.let { it to v } }
                    .filter { (d, _) -> d.isBefore(nextDate) }
                    .maxByOrNull { (d, _) -> d }?.second ?: allLabels.values.firstOrNull()

                // The requirement: "we do not count switching from a known location to the assumed location or back to the same location that is known and the same as the last assumed location as a change. it is only if it is actually a new place that it counts as a change."
                // Also: "count a place as the "same" place for our auto setting if the last two comma separated sections are the same."
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
    }

    // ── Day-driven accent colour ────────────────────────────────────────────
    // Light stats (points + monthly avg) are computed on every tick for the accent.
    // Full stats (streak/anti-streak) are debounced: only computed after the user
    // pauses on a day for 400ms, so rapid playback stays smooth.
    val lightStats = remember(selectedDate) { viewModel.getDayStatsLight(selectedDate) }
    val accent = remember(lightStats.monthlyAverage) { accentColorForPoints(lightStats.monthlyAverage.toInt()) }

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
    // Cached location label for the top bar (avoid SharedPrefs on every recomp).
    // Always show a label: use the exact entry for the day if present, otherwise
    // fall back to the most recent preceding entry and mark it as assumed (*).
    val locationLabelPair = remember(selectedDate, locationVersion) {
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
    val locationLabel = locationLabelPair.first
    val locationIsAssumed = locationLabelPair.second

    // ── Layout ──────────────────────────────────────────────────────────────
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0F1A)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MapTopBar(locationLabel = locationLabel, isAssumed = locationIsAssumed)

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
                        accent = accent,
                        speed = speed,
                        onSizeChanged = { mapSize = it }
                    )
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
                onScrub = { newDate ->
                    isPlaying = false
                    viewModel.navigateToDate(newDate)
                },
                onStepDay = { delta ->
                    isPlaying = false
                    val target = selectedDate.plusDays(delta.toLong())
                    val clamped = when {
                        target.isBefore(firstDate) -> firstDate
                        target.isAfter(lastDate) -> lastDate
                        else -> target
                    }
                    if (clamped != selectedDate) viewModel.navigateToDate(clamped)
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
private fun MapTopBar(locationLabel: String?, isAssumed: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111726))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Always show something — if no label at all, reserve space so layout
        // doesn't jump. If assumed (no exact entry for this day), append *.
        val display = when {
            locationLabel != null && isAssumed -> "  $locationLabel *"
            locationLabel != null              -> locationLabel
            else                               -> " "
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            // Spacer to push the text to the right edge of the map area.
            // The map area takes weight(1f) and the info panel takes 220.dp.
            // So we want the text to be aligned to the end of the weight(1f) section.
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = display,
                color = if (locationLabel != null) Color(0xFFAACCEE) else Color.Transparent,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 220.dp)
            )
        }
    }
}

// ── Map background + marker ─────────────────────────────────────────────────

@Composable
private fun WorldMapWithMarker(
    currentCoords: Pair<Double, Double>?,
    allCoordsTrail: Map<LocalDate, Pair<Double, Double>>,
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
            val dx = tx - animX.value
            val dy = ty - animY.value
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val msPerDay = (1000f / effectiveSpeed).toLong().coerceAtLeast(15L)
            val travelMs = requiredAnimMs(dist, lastSize.width)
            val durMs = kotlin.math.max(MIN_MARKER_ANIM_MS, kotlin.math.max(msPerDay, travelMs))
                .coerceAtMost(2000L)
                .toInt()
            // Animate X and Y in PARALLEL so the marker moves diagonally in a
            // straight line between two days.
            launch { animX.animateTo(tx, animationSpec = tween(durMs)) }
            launch { animY.animateTo(ty, animationSpec = tween(durMs)) }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF06101F))
            .onSizeChanged { newSize ->
                val s = Size(newSize.width.toFloat(), newSize.height.toFloat())
                lastSize = s
                onSizeChanged(s)
            }
    ) {

        // Continent fills.
        val landColor = Color(0xFF2A4060)
        val landStroke = Color(0xFF3F5C82)
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
        val gridColor = Color(0xFF1A2638)
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

        // Trail of all dots (every visited day) — small, dim, tinted with the
        // current-day accent so the whole map feels of-a-piece.
        val trailColor = accent.copy(alpha = 0.55f)
        for ((_, coord) in allCoordsTrail) {
            val (lat, lon) = coord
            drawCircle(
                color = trailColor,
                radius = 1.5f,
                center = Offset(lonToX(lon, size.width), latToY(lat, size.height))
            )
        }

        // Current-day marker — a stylised "person pin" (head + body), tinted
        // by the day's accent colour.
        if (currentCoords != null) {
            val cx = animX.value
            val cy = animY.value
            // Lighter "head" tone — blend accent → near-white.
            val headColor = Color(
                red = (accent.red + (1f - accent.red) * 0.45f).coerceIn(0f, 1f),
                green = (accent.green + (1f - accent.green) * 0.45f).coerceIn(0f, 1f),
                blue = (accent.blue + (1f - accent.blue) * 0.45f).coerceIn(0f, 1f),
                alpha = 1f
            )
            // Halo
            drawCircle(color = accent.halo(0.20f), radius = 20f, center = Offset(cx, cy))
            // Body
            drawCircle(color = accent, radius = 7.5f, center = Offset(cx, cy + 5f))
            // Head
            drawCircle(color = headColor, radius = 5.5f, center = Offset(cx, cy - 6f))
            // Outline
            drawCircle(
                color = Color(0xFF1A1408),
                radius = 7.5f,
                center = Offset(cx, cy + 5f),
                style = Stroke(width = 1.5f)
            )
        }
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
            .background(Color(0xFF0E1726))
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
        Text(text = label, color = Color(0xFF8899AA), fontSize = 12.sp, modifier = Modifier.weight(1f))
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
        Text(text = label, color = Color(0xFF8899AA), fontSize = 12.sp, modifier = Modifier.weight(1f))
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
                .background(Color(0xFF0D0D1A), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = accent, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (onEditIgnored != null) {
                    TextButton(onClick = onEditIgnored) {
                        Text("Edit", color = Color(0xFF6699BB), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF223344))
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
                .background(Color(0xFF0D0D1A), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Habits today", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF223344))
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
                .background(Color(0xFF0D0D1A), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text("Ignored country names", color = accent, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Names in this list are excluded from the countries count.",
                color = Color(0xFF778899),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF223344))
            Spacer(Modifier.height(6.dp))

            // ── Add row ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Add name…", fontSize = 12.sp, color = Color(0xFF556677)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color(0xFF334455),
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
                            Text("✕", color = Color(0xFF885555), fontSize = 13.sp)
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
    accent: Color
) {
    var showSpeedDropdown by remember { mutableStateOf(false) }

    val daysFromStart = java.time.temporal.ChronoUnit.DAYS
        .between(firstDate, selectedDate)
        .coerceIn(0L, totalDays.toLong())
        .toInt()
    val canStepBack = selectedDate.isAfter(firstDate)
    val canStepForward = selectedDate.isBefore(lastDate)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111726))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play / pause
            IconButton(onClick = onTogglePlay) {
                if (isPlaying) {
                    Text("⏸", color = accent, fontSize = 16.sp)
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
                    modifier = Modifier.background(Color(0xFF1A2638))
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
                    inactiveTrackColor = Color(0xFF334466)
                )
            )
            Spacer(Modifier.width(4.dp))
            // ── Step-by-day controls (sit right next to the day counter) ──
            IconButton(
                onClick = { if (canStepBack) onStepDay(-1) },
                enabled = canStepBack
            ) {
                Text(
                    text = "‹",
                    color = if (canStepBack) Color.White else Color(0xFF445566),
                    fontSize = 22.sp
                )
            }
            Text(
                text = "${daysFromStart} / $totalDays",
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp
            )
            IconButton(
                onClick = { if (canStepForward) onStepDay(1) },
                enabled = canStepForward
            ) {
                Text(
                    text = "›",
                    color = if (canStepForward) Color.White else Color(0xFF445566),
                    fontSize = 22.sp
                )
            }
        }
    }
}

package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.tail.data.ComplianceDay
import com.example.tail.data.GameFilter
import com.example.tail.data.Phase2AuditRecord
import com.example.tail.data.Phase2V2GameRecord
import com.example.tail.data.RatingHistoryPoint
import com.example.tail.data.RatingHistorySeries
import com.example.tail.data.RatingPoolStats
import com.example.tail.data.ReadinessBlockedRecord
import com.example.tail.data.ReadinessGameRecord
import com.example.tail.data.ReadinessStats
import com.example.tail.data.ReadinessTestRecord
import com.example.tail.data.Phase2Verdicts
import com.example.tail.data.V2PvtRecord
import com.example.tail.data.ReflexRunPoint
import com.example.tail.data.V3ReflexRunRecord
import com.example.tail.data.buildReflexRuns
import com.example.tail.data.computeReflexStats
import com.example.tail.data.PuzzleRushSessionRecord
import com.example.tail.data.computeBucketWinRates
import com.example.tail.data.computeComplianceSeries
import com.example.tail.data.computeDayOfWeekStats
import com.example.tail.data.computeGameCategoryAggregates
import com.example.tail.data.computeHourlyReadiness
import com.example.tail.data.computePhase2V2Stats
import com.example.tail.data.computePuzzleTimeSeries
import com.example.tail.data.computeRatingHistory
import com.example.tail.data.computeRushScoreSeries
import com.example.tail.data.computeRushSessionPoints
import com.example.tail.data.mergeRushSeries
import com.example.tail.data.rushReviewRate
import com.example.tail.data.computeRatingStats
import com.example.tail.data.computeReadinessStats
import com.example.tail.data.computeWinRateByCcrsBand
import com.example.tail.widget.ChessPhase2Store
import com.example.tail.widget.ChessPhase2V2Store
import com.example.tail.widget.ChessReadinessEngine
import com.example.tail.widget.ChessReadinessLogStore
import com.example.tail.widget.ChessReadinessSystemChanges
import com.example.tail.widget.ChessReadinessV2Store
import com.example.tail.widget.ReadinessSystemChange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Palette (soft/vague orange — a continuation of the Chess Readiness
//    settings section this screen is reached from). The effectiveness
//    sections file carries private copies of the same warm palette. ─────────
private val SectionTitleColor = Color(0xFFF2A65A)   // soft orange
private val LabelColor = Color(0xFFE6C79C)          // warm sand
private val ValueColor = Color.White
private val DimColor = Color(0xFF9C8B77)            // warm grey
private val SectionBg = Color(0xFF231A10)           // dark warm brown
private val DividerColor = Color(0xFF3A2E1E)        // warm divider
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val YellowValue = Color(0xFFEAB308)
private val GoldValue = Color(0xFFFFC24D)           // warm amber
private val LinkColor = Color(0xFFFFB066)           // light orange

private val EVENT_FMT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun stateColor(state: String?): Color = when (state) {
    ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name -> Color(0xFF22C55E)
    ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name -> YellowValue
    ChessReadinessEngine.ReadinessState.RED_LIGHT.name -> Color(0xFFEF4444)
    else -> DimColor
}

private fun stateLabel(state: String?): String = when (state) {
    ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name -> "GREEN"
    ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name -> "YELLOW"
    ChessReadinessEngine.ReadinessState.RED_LIGHT.name -> "RED"
    else -> "—"
}

/** Dot colours for the interactive Phase-2 chart (verdict → colour). */
private fun verdictDotColor(state: String?): Color = when (state) {
    Phase2Verdicts.CONTINUE -> Color(0xFF22C55E)
    Phase2Verdicts.PIVOT -> YellowValue
    Phase2Verdicts.TERMINATE -> Color(0xFFEF4444)
    else -> DimColor
}

/** "yyyy-MM-dd" day string → epoch ms at local midnight. */
private fun dayStartMs(dateStr: String): Long =
    LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Global variant toggle limiting EVERY game-based stat on this screen.
 * `key` is the chess.com variant slug matched against
 * [ReadinessGameRecord.variant] (null = no filtering).
 */
private enum class VariantOption(val label: String, val key: String?) {
    ALL("All games", null),
    CHESS960("Chess960", "chess960"),
    STANDARD("Standard", "chess")
}

/**
 * ♟ Chess Stats — the special screen fed by the detailed chess activity
 * log ([ChessReadinessLogStore]):
 *
 *  - readiness ratings over time (per-day average CCRS chart)
 *  - readiness by time of day (6 × 4-hour buckets)
 *  - games played inside valid GREEN authorization windows vs. without
 *    authorization, win rates in each case, and games per authorized session
 *  - Puzzle Rush stats (readiness-test runs + standalone timer sessions)
 *
 * Reached from the App Stats screen (Settings → App Stats → Chess Readiness).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessReadinessStatsScreen(
    onNavigateBack: () -> Unit,
    loadingMetrics: LoadingMetrics = LoadingMetrics(0.0, 0.0, 0)
) {
    val context = LocalContext.current

    // The screen stays PORTRAIT no matter how the phone is held —
    // landscape is entered ONLY by tapping a chart, which opens the
    // interactive full-screen popup (it locks sensor-landscape and
    // restores this portrait lock on dismiss).
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val chartHeight: Dp = 150.dp
    val deltaRowHeight = 46

    var tests by remember { mutableStateOf<List<ReadinessTestRecord>>(emptyList()) }
    var games by remember { mutableStateOf<List<ReadinessGameRecord>>(emptyList()) }
    var blocked by remember { mutableStateOf<List<ReadinessBlockedRecord>>(emptyList()) }
    // Standalone Puzzle Rush timer sessions (reported via the rush prompt).
    var rushSessions by remember {
        mutableStateOf<List<PuzzleRushSessionRecord>>(emptyList())
    }
    var systemStartMs by remember { mutableStateOf<Long?>(null) }
    // The currently-open interactive landscape chart (null = none).
    var interactiveChart by remember { mutableStateOf<InteractiveChartRequest?>(null) }
    var showComplianceZoom by remember { mutableStateOf(false) }
    // Game-subset filter for the grouped time-of-day section; also the
    // initial filter of the hourly win-rate popup.
    var bucketFilter by remember { mutableStateOf(GameFilter.ALL) }
    var showHourlyReadiness by remember { mutableStateOf(false) }
    var showHourlyWinRate by remember { mutableStateOf(false) }
    // Global variant filter limiting EVERY game-based stat on the screen
    // (readiness tests are variant-independent and stay unfiltered).
    var variantFilter by remember { mutableStateOf(VariantOption.ALL) }
    // Bumped every time the screen resumes, so the data below reloads
    // and the user never sees stale pre-backfill numbers.
    var resumeCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    // V2 system telemetry: the PVT-B reflex-run log (feeds the cross-version
    // reflex section) and the post-game audit (v2 ledger + shared audits),
    // mapped to the pure calculator's input records.
    var v2Pvt by remember { mutableStateOf<List<V2PvtRecord>>(emptyList()) }
    var phase2V2Games by remember { mutableStateOf<List<Phase2V2GameRecord>>(emptyList()) }
    var phase2Audits by remember { mutableStateOf<List<Phase2AuditRecord>>(emptyList()) }
    // v1↔v2 engine switch history → extra ◆ markers on the rating chart,
    // so it's always visible which system was active relative to rating
    // changes. Sourced from the switch logs both V2 stores keep.
    var versionSwitchMarkers by remember { mutableStateOf<List<ReadinessSystemChange>>(emptyList()) }
    // Currently ACTIVE engine versions — drive the default expansion of the
    // version-owned stats sections (active system expanded, other collapsed).
    var pregameIsV2 by remember { mutableStateOf(ChessReadinessV2Store.isV2(context)) }
    var pregameIsV3 by remember { mutableStateOf(ChessReadinessV2Store.isV3(context)) }
    var phase2IsV2 by remember { mutableStateOf(ChessPhase2V2Store.isV2(context)) }

    // Runs on first composition AND on every resume (via resumeCount).
    // Only raw data is loaded here; every aggregate is derived below so
    // toggling the variant filter recomputes instantly without I/O.
    LaunchedEffect(resumeCount) {
        withContext(Dispatchers.IO) {
            // One-time import of the legacy capped test history, so the
            // compliance chart knows when the system was adopted and
            // historical games get an accurate readiness context.
            ChessReadinessLogStore.ensureSeeded(context)
            tests = ChessReadinessLogStore.loadTests(context)
            games = ChessReadinessLogStore.loadGames(context)
            blocked = ChessReadinessLogStore.loadBlocked(context)
            rushSessions = ChessReadinessLogStore.loadRushSessions(context)
            systemStartMs = tests.minOfOrNull { it.timestamp }
            // V2 pre-game gate: PVT-B reflex runs (the verdict log itself is
            // no longer charted — the reflex section is version-agnostic).
            v2Pvt = ChessReadinessV2Store.loadPvt(context).map {
                V2PvtRecord(
                    timestamp = it.timestamp,
                    validResponses = it.validResponses,
                    lapses = it.lapses,
                    falseStarts = it.falseStarts,
                    meanRrt = it.meanRrt,
                    meanRtMs = it.meanRtMs,
                    maxRtMs = it.maxRtMs
                )
            }
            // V2 post-game audit: rated-game ledger joined (by timestamp,
            // in the calculator) to the shared Phase 2 audit history.
            phase2V2Games = ChessPhase2V2Store.loadRecentGames(context).map {
                Phase2V2GameRecord(
                    timestamp = it.timestamp,
                    result = it.result,
                    timeControl = it.timeControl,
                    outputState = it.outputState,
                    estimatedMinutes = it.estimatedMinutes
                )
            }
            phase2Audits = ChessPhase2Store.loadAudits(context).map {
                Phase2AuditRecord(
                    timestamp = it.timestamp,
                    timeControl = it.timeControl,
                    outputState = it.outputState,
                    deltaE = it.deltaE,
                    caps2Accuracy = it.caps2Accuracy,
                    accuracyCounted = it.accuracyCounted,
                    strain = it.strain
                )
            }
            // v1↔v2 engine switches (pre-game + post-game toggles) become
            // tappable markers on the "Rating Since Readiness System" chart.
            val switchFmt = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")
            fun switchTime(ts: Long) =
                Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(switchFmt)
            versionSwitchMarkers =
                ChessReadinessV2Store.loadVersionSwitches(context).map {
                    val toV2 = it.version == ChessReadinessV2Store.VERSION_V2
                    ReadinessSystemChange(
                        timestampMs = it.timestampMs,
                        title = if (toV2) "Pre-game engine switched → V2"
                                else "Pre-game engine switched → V1",
                        description =
                            (if (toV2)
                                "You toggled the PRE-GAME readiness test from v1 to v2 — from " +
                                    "this moment the neurobiological gate (autonomic Z-scores + " +
                                    "ACWR + PVT-B reflex test) ran your pre-game checks. "
                            else
                                "You toggled the PRE-GAME readiness test back from v2 to v1 — " +
                                    "from this moment the original survey + puzzle diagnostic " +
                                    "ran your pre-game checks. ") +
                            "Switched at " + switchTime(it.timestampMs) + "."
                    )
                } + ChessPhase2V2Store.loadVersionSwitches(context).map {
                    val toV2 = it.version == ChessPhase2V2Store.VERSION_V2
                    ReadinessSystemChange(
                        timestampMs = it.timestampMs,
                        title = if (toV2) "Post-game audit switched → V2"
                                else "Post-game audit switched → V1",
                        description =
                            (if (toV2)
                                "You toggled the POST-GAME (Phase 2) audit from v1 to v2 — from " +
                                    "this moment rated games were reviewed by the " +
                                    "research-report system (fatigue ceiling, loss-streak stop " +
                                    "rules, tilt vector, ACWR, hysteresis). "
                            else
                                "You toggled the POST-GAME (Phase 2) audit back from v2 to v1 — " +
                                    "from this moment the adaptive ΔE/strain evidence model " +
                                    "reviewed rated games. ") +
                            "Switched at " + switchTime(it.timestampMs) + "."
                    )
                }
            pregameIsV2 = ChessReadinessV2Store.isV2(context)
            pregameIsV3 = ChessReadinessV2Store.isV3(context)
            phase2IsV2 = ChessPhase2V2Store.isV2(context)
            loaded = true
        }
    }

    // Every game-based stat on the screen flows through the variant filter.
    val visibleGames = remember(games, variantFilter) {
        val key = variantFilter.key
        if (key == null) games
        else games.filter { it.variant.equals(key, ignoreCase = true) }
    }
    val stats = remember(tests, visibleGames, blocked) {
        computeReadinessStats(tests, visibleGames, blocked, ZoneId.systemDefault())
    }
    val complianceDays = remember(visibleGames, tests, systemStartMs) {
        computeComplianceSeries(visibleGames, tests, systemStartMs ?: 0L, ZoneId.systemDefault())
    }
    val ratingPools = remember(visibleGames, tests, systemStartMs) {
        computeRatingStats(visibleGames, tests, systemStartMs ?: 0L)
    }
    val ratingHistory = remember(visibleGames) { computeRatingHistory(visibleGames) }
    // V2 post-game aggregate — variant-independent (the audit ledger covers
    // every rated game regardless of variant).
    val phase2V2Stats = remember(phase2V2Games, phase2Audits) {
        computePhase2V2Stats(phase2V2Games, phase2Audits)
    }
    // Static rule-change registry + the user's own engine switches, oldest
    // first — one ◆ marker each on the "since system" rating chart.
    val allSystemChanges = remember(versionSwitchMarkers) {
        (ChessReadinessSystemChanges.ALL + versionSwitchMarkers).sortedBy { it.timestampMs }
    }
    // Sections of the v1 readiness system collapse away while the v2/v3
    // pre-game engine is the active one (and vice versa).
    val v1SectionsExpanded = !pregameIsV2 && !pregameIsV3

    // V3 (reflex + survival gate) telemetry.
    val v3Results = remember(resumeCount) {
        com.example.tail.widget.ChessReadinessV3Store.loadResults(context)
    }
    val v3Events = remember(resumeCount) {
        com.example.tail.widget.ChessReadinessV3Store.loadEvents(context)
    }

    // Cross-version reflex series: every PVT-B run ever recorded (v2's
    // 3-minute runs + v3's 2-minute runs), with the rated-game log joined
    // in for the "following session" correlation.
    val reflexRuns = remember(v2Pvt, v3Results) {
        buildReflexRuns(
            v2Pvt = v2Pvt,
            v3Reflex = v3Results.map {
                V3ReflexRunRecord(
                    timestamp = it.timestamp,
                    lapses = it.reflexLapses,
                    falseStarts = it.reflexFalseStarts,
                    meanRtMs = it.reflexMeanRtMs
                )
            }
        )
    }
    val reflexStats = remember(reflexRuns, games) {
        computeReflexStats(reflexRuns, games)
    }

    // Reload when the screen resumes — e.g. when returning after the
    // chess.com full-history backfill finished while this screen was open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Color(0xFF120E08),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "♟ Chess Stats",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B140C)
                )
            )
        }
    ) { paddingValues ->
        val s = stats
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (!loaded) {
                // The shared color-based loading animation ("The Orrery")
                // instead of a stalling blank screen while the log loads.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    HabitLoadingSpinner(
                        monthlyAverage = loadingMetrics.monthlyAverage,
                        weeklyAverage = loadingMetrics.weeklyAverage,
                        todayPoints = loadingMetrics.todayPoints,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else if (tests.isEmpty() && games.isEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                StatsSection(title = "♟ Chess Readiness") {
                    Text(
                        "No readiness activity logged yet.\n\n" +
                            "Every readiness test (with full telemetry), every chess.com " +
                            "game with its readiness context, and every blocked test " +
                            "attempt is logged automatically once you use the feature.",
                        color = DimColor,
                        fontSize = 13.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Global variant filter ─────────────────────────────────
                StatsSection(title = "🎯 Variant Filter") {
                    Text(
                        "Limits every game-based stat on this screen to one " +
                            "chess.com variant — readiness tests are variant-" +
                            "independent and stay unfiltered. Chess960 has a " +
                            "single official rating; Standard has one per speed.",
                        color = DimColor,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    VariantFilterSelector(
                        selected = variantFilter,
                        onSelect = { variantFilter = it },
                        counts = remember(games) {
                            VariantOption.entries.associateWith { o ->
                                val key = o.key
                                if (key == null) games.size
                                else games.count { g -> g.variant.equals(key, ignoreCase = true) }
                            }
                        }
                    )
                }

                // ── Overview ──────────────────────────────────────────────
                StatsSection(title = "📊 Readiness Overview", startExpanded = v1SectionsExpanded) {
                    StatRow("Tests logged", s.totalTests.toString())
                    StatRow("Average CCRS", "%.1f".format(s.avgCcrs), valueColor = GoldValue)
                    StatRow(
                        "Best score",
                        "${s.bestCcrs} (${fmtTime(s.bestTestAt)})",
                        valueColor = GreenValue
                    )
                    StatRow(
                        "Worst score",
                        "${s.worstCcrs} (${fmtTime(s.worstTestAt)})",
                        valueColor = RedValue
                    )
                    StatRow("🟢 Green authorizations", s.greenCount.toString(), valueColor = GreenValue)
                    StatRow("🟡 Yellow (casual only)", s.yellowCount.toString(), valueColor = YellowValue)
                    StatRow("🔴 Red (all prohibited)", s.redCount.toString(), valueColor = RedValue)
                    StatRow("Blocked test attempts", s.blockedAttempts.toString(), valueColor = DimColor)
                    if (s.totalTests > 0) {
                        StatRow("Avg test duration", "%.1f min".format(s.avgTestDurationMin))
                        StatRow("First test", fmtTime(s.firstTestAt))
                        StatRow("Last test", fmtTime(s.lastTestAt))
                    }
                }

                // ── Readiness over time ───────────────────────────────────
                if (s.dailyAvgCcrs.size > 1) {
                    StatsSection(title = "📈 Readiness Over Time", startExpanded = v1SectionsExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    interactiveChart = InteractiveChartRequest(
                                        title = "♟ Readiness — avg CCRS per day",
                                        series = listOf(
                                            IChartSeries(
                                                name = "Avg CCRS",
                                                color = Color(0xFFF2994A),
                                                points = s.dailyAvgCcrs.map { (d, v) ->
                                                    IChartPoint(dayStartMs(d), v.toDouble())
                                                }
                                            )
                                        ),
                                        valueFormat = "%.1f"
                                    )
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Average CCRS per day",
                                color = LabelColor,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${s.dailyAvgCcrs.size} days 📈",
                                color = LinkColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            )
                        }
                        Text(
                            "Tap for the interactive landscape chart — pinch to zoom, " +
                                "drag to scroll, tap any point for its exact score and date.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                    }
                }

                // ── Rated puzzle times over time ───────────────────────────
                val puzzlePoints = remember(tests) { computePuzzleTimeSeries(tests) }
                if (puzzlePoints.size >= 2) {
                    StatsSection(title = "🧩 Rated Puzzle Times Over Time", startExpanded = v1SectionsExpanded) {
                        Text(
                            "Average solve time of the rated puzzles from each readiness " +
                                "test, oldest to newest. Tap a point for that test's " +
                                "individual puzzle times and readiness context.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val puzzleChunks = remember(puzzlePoints) {
                            puzzlePoints.chunked(MAX_POINTS_PER_CHART)
                        }
                        puzzleChunks.forEachIndexed { ci, chunk ->
                            if (puzzleChunks.size > 1) {
                                Text(
                                    "${formatDateShort(chunk.first().timestampMs)} – " +
                                        formatDateShort(chunk.last().timestampMs),
                                    color = DimColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            PuzzleTimesChart(chunk, chartHeight)
                            if (ci < puzzleChunks.size - 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        ChartLinkRow(
                            "Zoomable puzzle-time chart — pinch, scroll, tap any test",
                            "Interactive 📈"
                        ) {
                            interactiveChart = InteractiveChartRequest(
                                title = "🧩 Rated puzzle times per test",
                                series = listOf(
                                    IChartSeries(
                                        name = "Avg solve time (s)",
                                        color = Color(0xFFF2994A),
                                        points = puzzlePoints.map {
                                            IChartPoint(it.timestampMs, it.avgSec.toDouble())
                                        }
                                    )
                                ),
                                valueFormat = "%.1f",
                                valueUnit = " s"
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val allTimes = puzzlePoints.flatMap { it.timesSec }
                        val firstAvg = puzzlePoints.take(3).map { it.avgSec }.average()
                        val lastAvg = puzzlePoints.takeLast(3).map { it.avgSec }.average()
                        StatRow(
                            "Average solve time",
                            "%.1f s".format(allTimes.average()),
                            valueColor = GoldValue
                        )
                        StatRow("Best single puzzle", "${allTimes.min()} s", valueColor = GreenValue)
                        StatRow("Latest test average", "%.1f s".format(puzzlePoints.last().avgSec))
                        StatRow(
                            "Trend (first 3 vs last 3 tests)",
                            "%+.1f s".format(lastAvg - firstAvg),
                            valueColor = if (lastAvg <= firstAvg) GreenValue else RedValue
                        )
                    }
                }

                // ── Puzzle rush over time ──────────────────────────────────
                // Merged series: rush runs reported inside v1 readiness
                // tests + standalone Puzzle Rush timer sessions.
                val rushPoints = remember(tests, rushSessions) {
                    mergeRushSeries(
                        computeRushScoreSeries(tests),
                        computeRushSessionPoints(rushSessions)
                    )
                }
                if (rushPoints.size >= 2) {
                    StatsSection(title = "⚡ Puzzle Rush Over Time", startExpanded = v1SectionsExpanded) {
                        Text(
                            "Puzzle Rush score (puzzles solved in a 3-minute run) from " +
                                "each readiness test and each standalone Puzzle Rush timer " +
                                "session, oldest to newest. Dashed gold line = all-time " +
                                "record. Tap a point for strikes, review status and record.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val rushChunks = remember(rushPoints) {
                            rushPoints.chunked(MAX_POINTS_PER_CHART)
                        }
                        rushChunks.forEachIndexed { ci, chunk ->
                            if (rushChunks.size > 1) {
                                Text(
                                    "${formatDateShort(chunk.first().timestampMs)} – " +
                                        formatDateShort(chunk.last().timestampMs),
                                    color = DimColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            RushScoreChart(chunk, chartHeight)
                            if (ci < rushChunks.size - 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        ChartLinkRow(
                            "Zoomable rush chart — pinch, scroll, tap any test",
                            "Interactive 📈"
                        ) {
                            interactiveChart = InteractiveChartRequest(
                                title = "⚡ Puzzle Rush score per test",
                                series = listOf(
                                    IChartSeries(
                                        name = "Rush score",
                                        color = GoldValue,
                                        points = rushPoints.map {
                                            IChartPoint(it.timestampMs, it.score.toDouble())
                                        }
                                    )
                                ),
                                valueFormat = "%.0f",
                                yIncludeZero = true
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val scores = rushPoints.map { it.score }
                        StatRow("Best score", scores.max().toString(), valueColor = GoldValue)
                        StatRow("Average score", "%.1f".format(scores.average()))
                        StatRow("Latest score", rushPoints.last().score.toString())
                        StatRow(
                            "All-time record",
                            rushPoints.maxOf { maxOf(it.score, it.allTimeHigh) }.toString(),
                            valueColor = GoldValue
                        )
                        // Review discipline — only timer sessions report it.
                        rushReviewRate(rushPoints)?.let { rate ->
                            StatRow(
                                "Reviewed wrong puzzles",
                                "%.0f%%".format(rate),
                                valueColor = if (rate >= 50.0) GreenValue else RedValue
                            )
                        }
                        val timerDurations = rushPoints.mapNotNull { it.durationSec }
                        if (timerDurations.isNotEmpty()) {
                            val avgSec = timerDurations.average().toLong()
                            StatRow(
                                "Avg timer session",
                                "%d:%02d".format(avgSec / 60, avgSec % 60)
                            )
                        }
                    }
                }

                // ── Time of day ───────────────────────────────────────────
                if (s.totalTests > 0) {
                    StatsSection(title = "🕐 Readiness by Time of Day", startExpanded = v1SectionsExpanded) {
                        // Games column follows the selected game subset.
                        val bucketWinRates = remember(visibleGames, bucketFilter) {
                            computeBucketWinRates(visibleGames, bucketFilter)
                        }
                        val filterCounts = remember(visibleGames) {
                            GameFilter.entries.associateWith { f -> visibleGames.count { f.matches(it) } }
                        }
                        Text(
                            "Games column filter (readiness columns stay on all tests):",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        GameFilterSelector(
                            selected = bucketFilter,
                            onSelect = { bucketFilter = it },
                            counts = filterCounts
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        s.timeBuckets.forEachIndexed { i, b ->
                            val isBest = b.testCount > 0 && b.label == s.bestBucketLabel
                            val isWorst = b.testCount > 0 && b.label == s.worstBucketLabel &&
                                s.bestBucketLabel != s.worstBucketLabel
                            val bw = bucketWinRates[i]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    b.label + if (isBest) " ⭐" else if (isWorst) " ▼" else "",
                                    color = LabelColor,
                                    fontSize = 12.sp,
                                    fontWeight = if (isBest || isWorst) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(56.dp)
                                )
                                Text(
                                    if (b.testCount > 0) "%.1f".format(b.avgCcrs) else "—",
                                    color = when {
                                        isBest -> GoldValue
                                        isWorst -> RedValue
                                        else -> ValueColor
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(44.dp)
                                )
                                Text(
                                    "${b.testCount} tests",
                                    color = DimColor,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${bw.games} games" +
                                        if (bw.games > 0) " · ${bw.wins * 100 / bw.games}% win" else "",
                                    color = DimColor,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (s.bestBucketLabel != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            StatRow("Best time of day", s.bestBucketLabel ?: "—", valueColor = GoldValue)
                            StatRow("Worst time of day", s.worstBucketLabel ?: "—", valueColor = RedValue)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        ChartLinkRow(
                            "Average CCRS by hour of day (00–23)",
                            "24-hour chart 📈"
                        ) { showHourlyReadiness = true }
                        ChartLinkRow(
                            "Win rate by hour of day (00–23)",
                            "24-hour chart 📈"
                        ) { showHourlyWinRate = true }
                        Text(
                            "Both open a full-screen landscape chart; the win-rate chart " +
                                "has the same All / Post-test / Approved / Unapproved filters.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                    }
                }

                // ── Games vs readiness ────────────────────────────────────
                if (s.totalGames > 0) {
                    StatsSection(title = "🎮 Games vs Authorization", startExpanded = v1SectionsExpanded) {
                        StatRow("Games logged", s.totalGames.toString())
                        StatRow(
                            "Played while authorized (Green)",
                            s.gamesAuthorized.toString(),
                            valueColor = GreenValue
                        )
                        StatRow(
                            "Played WITHOUT authorization",
                            s.gamesUnauthorized.toString(),
                            valueColor = if (s.gamesUnauthorized > 0) RedValue else DimColor
                        )
                        StatRow(
                            "Played before any test existed",
                            s.gamesNoTest.toString(),
                            valueColor = DimColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        StatRow(
                            "Protocol compliance",
                            "%.0f%%".format(s.complianceRate),
                            valueColor = when {
                                s.complianceRate >= 90 -> GreenValue
                                s.complianceRate >= 60 -> YellowValue
                                else -> RedValue
                            }
                        )
                        StatRow(
                            "Win rate (authorized)",
                            "%.1f%%".format(s.winRateAuthorized),
                            valueColor = GreenValue
                        )
                        if (s.gamesUnauthorized > 0) {
                            StatRow(
                                "Win rate (unauthorized)",
                                "%.1f%%".format(s.winRateUnauthorized),
                                valueColor = RedValue
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        StatRow("Green sessions used for play", s.greenSessions.toString())
                        StatRow(
                            "Avg games per authorized session",
                            "%.1f".format(s.avgGamesPerGreenSession)
                        )
                        StatRow(
                            "Most games in one session",
                            s.maxGamesInOneGreenSession.toString(),
                            valueColor = GoldValue
                        )
                    }
                }

                // ── System effectiveness ────────────────────────────────
                if (s.totalGames > 0) {
                    val aggregates = remember(visibleGames, tests) {
                        computeGameCategoryAggregates(visibleGames, tests)
                    }
                    SystemEffectivenessSection(
                        aggregates = aggregates,
                        totalGames = s.totalGames,
                        totalWins = visibleGames.count { it.won },
                        startExpanded = v1SectionsExpanded
                    )
                    CcrsBandSection(
                        bands = remember(visibleGames) { computeWinRateByCcrsBand(visibleGames) },
                        startExpanded = v1SectionsExpanded
                    )
                }

                // ── Day of week ──────────────────────────────────────────
                if (s.totalTests > 0) {
                    DayOfWeekSection(
                        stats = remember(tests, visibleGames) { computeDayOfWeekStats(tests, visibleGames) },
                        startExpanded = v1SectionsExpanded
                    )
                }

                // ── Compliance over time ─────────────────────────────────
                val start = systemStartMs
                if (start != null) {
                    StatsSection(title = "⚖️ Compliance Over Time", startExpanded = v1SectionsExpanded) {
                        Text(
                            "Games per day since the readiness system was adopted " +
                                "(${fmtDate(start)}). Green = played while authorized; " +
                                "red = violation. Games from before the system " +
                                "existed are excluded.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (complianceDays.isEmpty()) {
                            Text(
                                "No games played since the first readiness test.",
                                color = DimColor,
                                fontSize = 12.sp
                            )
                        } else {
                            ComplianceBarChart(
                                complianceDays,
                                chartHeight,
                                onOpen = { showComplianceZoom = true }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            ComplianceLegend()
                            Text(
                                "Tap the chart for the interactive landscape version — " +
                                    "pinch to zoom, drag to scroll, tap a bar for that " +
                                    "day's exact counts.",
                                color = DimColor,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            val auth = complianceDays.sumOf { it.authorized }
                            val denied = complianceDays.sumOf { it.violationDenied }
                            val noTest = complianceDays.sumOf { it.violationNoTest }
                            val totalPost = auth + denied + noTest
                            val pct = if (totalPost > 0) auth * 100.0 / totalPost else 100.0
                            StatRow(
                                "Compliance since adoption",
                                "%.0f%%".format(pct),
                                valueColor = when {
                                    pct >= 90 -> GreenValue
                                    pct >= 60 -> YellowValue
                                    else -> RedValue
                                }
                            )
                            StatRow("Authorized play", auth.toString(), valueColor = GreenValue)
                            StatRow(
                                "Played despite a blocking test",
                                denied.toString(),
                                valueColor = if (denied > 0) RedValue else DimColor
                            )
                            StatRow(
                                "Played without a fresh test",
                                noTest.toString(),
                                valueColor = if (noTest > 0) RedValue else DimColor
                            )
                        }
                    }
                }

                // ── Rating history (entire history) ──────────────────────
                // ratingHistory / ratingPools are already limited by the
                // global variant filter at the top of the screen (both are
                // derived from visibleGames).
                if (ratingHistory.any { it.points.size >= 2 }) {
                    StatsSection(title = "📜 Rating History — Entire History") {
                        Text(
                            "Rating over time from every rated game on chess.com, back to " +
                                "account creation. Standard chess has a pool per speed, " +
                                "while variants like Chess960 have a single chess.com " +
                                "rating shared across speeds — use the variant filter at " +
                                "the top of the screen to focus on one. The gold dashed " +
                                "line marks when the readiness test system was devised.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val markers = systemStartMs
                            ?.let { listOf(it to "♟ system") }
                            ?: emptyList()
                        ratingHistory.forEach { s ->
                            if (s.points.size < 2) return@forEach
                            Text(
                                "${s.label} — ${s.points.size} rated games",
                                color = ValueColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            RatingHistoryChart(
                                points = s.points,
                                markers = markers,
                                chartHeight = chartHeight,
                                onOpen = {
                                    interactiveChart = InteractiveChartRequest(
                                        title = "📜 Rating — ${s.label}",
                                        series = listOf(
                                            IChartSeries(
                                                name = s.label,
                                                color = BarGreen,
                                                points = s.points.map {
                                                    IChartPoint(
                                                        it.endTimeMs,
                                                        it.rating.toDouble(),
                                                        color = if (it.authorized) BarGreen else BarRed
                                                    )
                                                }
                                            )
                                        ),
                                        markers = markers.map { (ts, lbl) ->
                                            IChartMarker(ts, lbl)
                                        },
                                        valueFormat = "%.0f"
                                    )
                                }
                            )
                            StatRow("Start → Now", "${s.startRating} → ${s.endRating}")
                            StatRow(
                                "Peak / Low",
                                "${s.peakRating} / ${s.lowRating}",
                                valueColor = GoldValue
                            )
                        }
                    }
                }

                // ── Rating since readiness system (clickable change markers) ─
                val startMs = systemStartMs
                if (startMs != null && startMs > 0) {
                    val sinceSeries = ratingHistory.mapNotNull { s ->
                        val pts = s.points.filter { it.endTimeMs >= startMs }
                        if (pts.size < 2) null else RatingHistorySeries(
                            label = s.label,
                            key = s.key,
                            points = pts,
                            startRating = pts.first().rating,
                            endRating = pts.last().rating,
                            peakRating = pts.maxOf { it.rating },
                            lowRating = pts.minOf { it.rating }
                        )
                    }
                    if (sinceSeries.isNotEmpty()) {
                        StatsSection(title = "📜 Rating Since Readiness System ♟", startExpanded = true) {
                            Text(
                                "The same rating timeline, zoomed to the period since " +
                                    "the first recorded readiness test. Each segment is " +
                                    "colored by the game that produced it: green = played " +
                                    "while authorized, red = played without authorization. " +
                                    "Gold ◆ points mark system rule changes AND every " +
                                        "v1↔v2 engine switch (pre-game and post-game) — tap " +
                                        "one to read what changed and when it took effect; " +
                                        "tap anywhere else for the interactive zoomable chart.",
                                color = DimColor,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            sinceSeries.forEach { s ->
                                Text(
                                    "${s.label} — since adoption",
                                    color = ValueColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                RatingSinceSystemChart(
                                    points = s.points,
                                    changes = allSystemChanges,
                                    systemStartMs = startMs,
                                    chartHeight = chartHeight,
                                    onOpenZoom = {
                                        interactiveChart = InteractiveChartRequest(
                                            title = "📜 Rating since readiness system — ${s.label}",
                                            series = listOf(
                                                IChartSeries(
                                                    name = s.label,
                                                    color = BarGreen,
                                                    points = s.points.map {
                                                        IChartPoint(
                                                            it.endTimeMs,
                                                            it.rating.toDouble(),
                                                            color = if (it.authorized) BarGreen else BarRed
                                                        )
                                                    }
                                                )
                                            ),
                                            markers = allSystemChanges.map {
                                                IChartMarker(
                                                    it.timestampMs, "◆", it.title, it.description
                                                )
                                            },
                                            valueFormat = "%.0f"
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    LegendSwatch(BarGreen, "Authorized games")
                                    LegendSwatch(BarRed, "Played without authorization")
                                }
                                StatRow("Adoption → Now", "${s.startRating} → ${s.endRating}")
                                StatRow(
                                    "Peak / Low",
                                    "${s.peakRating} / ${s.lowRating}",
                                    valueColor = GoldValue
                                )
                            }
                        }
                    }
                }

                // ── Rating impact (compliant vs not) ─────────────────────
                if (ratingPools.isNotEmpty()) {
                    StatsSection(title = "🏆 Rating Impact — ${variantFilter.label}", startExpanded = v1SectionsExpanded) {
                        Text(
                            "Average rating change per game, split by compliance. Each pair " +
                                "of bars is one rating pool (Standard per speed; variants " +
                                "like Chess960 form one pool — chess.com gives them a single " +
                                "rating). Bars extend right for gains, left for losses.",
                            color = DimColor,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        RatingDeltaChart(ratingPools, deltaRowHeight)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LegendSwatch(BarGreen, "Authorized")
                            LegendSwatch(BarRed, "Violations")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        ratingPools.forEach { p ->
                            Text(
                                "${p.label} — ${p.ratedGames} rated · now ${p.currentRating ?: "—"}",
                                color = ValueColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            StatRow("✓ Authorized games", fmtRatingDelta(p.authorized), valueColor = GreenValue)
                            StatRow("✗ Violation games", fmtRatingDelta(p.violations), valueColor = RedValue)
                        }
                        val aGames = ratingPools.sumOf { it.authorized.games }
                        val vGames = ratingPools.sumOf { it.violations.games }
                        if (aGames > 0 && vGames > 0) {
                            val aAvg = ratingPools.sumOf { it.authorized.totalDelta }.toDouble() / aGames
                            val vAvg = ratingPools.sumOf { it.violations.totalDelta }.toDouble() / vGames
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            StatRow(
                                "Overall — avg/game authorized",
                                "%+.1f pts".format(aAvg),
                                valueColor = GreenValue
                            )
                            StatRow(
                                "Overall — avg/game violations",
                                "%+.1f pts".format(vAvg),
                                valueColor = RedValue
                            )
                            StatRow(
                                "Readiness advantage",
                                "%+.1f pts/game".format(aAvg - vAvg),
                                valueColor = if (aAvg >= vAvg) GoldValue else RedValue
                            )
                        }
                    }
                }

                // ── V3 system: reflex + survival pre-game gate ───────────
                // Dedicated section for the v3 chess-readiness system
                // (renders nothing until the first v3 record exists).
                V3PregameSection(
                    results = v3Results,
                    events = v3Events,
                    startExpanded = pregameIsV3
                )

                // ── Reflex tests (cross-version) ─────────────────────────
                // Every PVT-B reflex run ever recorded, regardless of engine
                // version — the long-term comparable metric (renders nothing
                // until the first reflex run exists).
                ReflexSection(
                    stats = reflexStats,
                    series = reflexRuns,
                    chartHeight = chartHeight,
                    startExpanded = pregameIsV2 || pregameIsV3,
                    onOpenRtChart = {
                        interactiveChart = InteractiveChartRequest(
                            title = "⚡ Reflex mean response time per run (all versions)",
                            series = listOf(
                                IChartSeries(
                                    name = "Mean RT (ms)",
                                    color = Color(0xFFF2994A),
                                    points = reflexRuns
                                        .filter { it.meanRtMs != null }
                                        .map {
                                            IChartPoint(it.timestampMs, it.meanRtMs!!)
                                        }
                                )
                            ),
                            valueFormat = "%.0f",
                            valueUnit = " ms"
                        )
                    },
                    onOpenLapseChart = {
                        interactiveChart = InteractiveChartRequest(
                            title = "⚡ Reflex late & early taps per run (all versions)",
                            series = listOf(
                                IChartSeries(
                                    name = "Late taps (≥355 ms)",
                                    color = Color(0xFFEF4444),
                                    points = reflexRuns.map {
                                        IChartPoint(it.timestampMs, it.lapses.toDouble())
                                    }
                                ),
                                IChartSeries(
                                    name = "Early taps (<100 ms)",
                                    color = YellowValue,
                                    points = reflexRuns.map {
                                        IChartPoint(it.timestampMs, it.falseStarts.toDouble())
                                    }
                                )
                            ),
                            valueFormat = "%.0f",
                            yIncludeZero = true
                        )
                    }
                )
                Phase2V2Section(
                    stats = phase2V2Stats,
                    chartHeight = chartHeight,
                    startExpanded = phase2IsV2,
                    onOpenAccuracyChart = {
                        interactiveChart = InteractiveChartRequest(
                            title = "🎯 Accuracy per audited game",
                            series = listOf(
                                IChartSeries(
                                    name = "Accuracy (%)",
                                    color = Color(0xFFF2994A),
                                    points = phase2V2Stats.series
                                        .filter { it.accuracy != null }
                                        .map {
                                            IChartPoint(
                                                it.timestampMs,
                                                it.accuracy!!,
                                                color = verdictDotColor(it.outputState)
                                            )
                                        }
                                )
                            ),
                            valueFormat = "%.1f",
                            valueUnit = "%"
                        )
                    }
                )

                // ── Sub-score breakdown ───────────────────────────────────
                if (s.totalTests > 0) {
                    StatsSection(title = "🧩 Sub-Score Averages (of 25)", startExpanded = v1SectionsExpanded) {
                        StatRow("😴 Sleep", "%.1f".format(s.avgSleepPts))
                        StatRow("🧠 Clarity", "%.1f".format(s.avgClarityPts))
                        StatRow("♟ Rated puzzles", "%.1f".format(s.avgPuzzlePts))
                        StatRow("⚡ Puzzle Rush", "%.1f".format(s.avgRushPts))
                    }
                }

                // ── Recent activity ───────────────────────────────────────
                StatsSection(title = "📋 Recent Activity") {
                    val recentTests = tests.takeLast(40).map {
                        Triple(it.timestamp, "test", it as Any)
                    }
                    val recentGames = visibleGames.takeLast(40).map {
                        Triple(it.endTimeMs, "game", it as Any)
                    }
                    val merged = (recentTests + recentGames)
                        .sortedByDescending { it.first }
                        .take(30)
                    if (merged.isEmpty()) {
                        Text("Nothing logged yet.", color = DimColor, fontSize = 12.sp)
                    }
                    merged.forEach { (_, kind, event) ->
                        when (kind) {
                            "test" -> {
                                val t = event as ReadinessTestRecord
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "♟ Test · ${fmtTime(t.timestamp)}",
                                        color = LabelColor,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${t.ccrs} ${stateLabel(t.state)}",
                                        color = stateColor(t.state),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            else -> {
                                val g = event as ReadinessGameRecord
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val badge = when {
                                        g.authorized -> " ✓auth"
                                        g.stateAtPlay != null -> " ✗no-auth"
                                        else -> ""
                                    }
                                    Text(
                                        "vs ${g.opponent} · ${g.type.lowercase()} · " +
                                            "${if (g.won) "win" else "loss/draw"}$badge",
                                        color = LabelColor,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        fmtTime(g.endTimeMs),
                                        color = DimColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    interactiveChart?.let { req ->
        InteractiveChartPopup(request = req, onDismiss = { interactiveChart = null })
    }

    if (showComplianceZoom) {
        ComplianceChartPopup(days = complianceDays, onDismiss = { showComplianceZoom = false })
    }

    if (showHourlyReadiness) {
        HourlyReadinessChartPopup(
            hourly = remember(tests) { computeHourlyReadiness(tests) },
            overallAvgCcrs = stats.avgCcrs,
            onDismiss = { showHourlyReadiness = false }
        )
    }

    if (showHourlyWinRate) {
        HourlyWinRateChartPopup(
            games = visibleGames,
            initialFilter = bucketFilter,
            onDismiss = { showHourlyWinRate = false }
        )
    }

}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Segmented All / Chess960 / Standard toggle at the top of the screen.
 * Wraps via [FlowRow] so it stays well-formed even on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantFilterSelector(
    selected: VariantOption,
    onSelect: (VariantOption) -> Unit,
    counts: Map<VariantOption, Int>
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VariantOption.entries.forEach { o ->
            val active = o == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Color(0xFF3A2A14) else Color(0xFF1B140C))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "${o.label} (${counts[o] ?: 0})",
                    color = if (active) GoldValue else LabelColor,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun fmtTime(ts: Long?): String =
    ts?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(EVENT_FMT)
    } ?: "—"

@Composable
private fun StatsSection(
    title: String,
    startExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSectionExpansion("chess", title, startExpanded)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(SectionBg, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = SectionTitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (expanded) "▼" else "▶", color = SectionTitleColor, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
        if (expanded) content()
    }
}

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

// ── Compliance chart ─────────────────────────────────────────────────────────

private val BarGreen = Color(0xFF22C55E)
private val BarRed = Color(0xFFEF4444)
private val DAY_FMT = DateTimeFormatter.ofPattern("d/M")

private fun fmtDate(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy"))

/**
 * Stacked daily bars for the compliance-over-time chart: the green part of
 * each bar counts games played while authorized, the red part on top counts
 * violations (played when a fresh test denied it, or without any fresh test).
 */
@Composable
private fun ComplianceBarChart(
    days: List<ComplianceDay>,
    chartHeight: Dp = 150.dp,
    /** When set, tapping the chart opens the interactive landscape view. */
    onOpen: (() -> Unit)? = null
) {
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    var canvasMod = Modifier.fillMaxWidth().height(chartHeight)
    if (onOpen != null) {
        canvasMod = canvasMod.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onOpen() }
    }
    Canvas(modifier = canvasMod) {
        if (days.isEmpty()) return@Canvas
        val chartLeft = 30.dp.toPx()
        val padRight = 6.dp.toPx()
        val padBottom = 20.dp.toPx()
        val padTop = 6.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - chartLeft - padRight
        val chartH = h - padBottom - padTop
        val bottom = h - padBottom
        val maxTotal = maxOf(1, days.maxOf { it.total })

        // Gridlines + y-axis labels at a nice step
        val step = when {
            maxTotal <= 5 -> 1
            maxTotal <= 10 -> 2
            maxTotal <= 25 -> 5
            maxTotal <= 50 -> 10
            else -> 25
        }
        var v = 0
        while (v <= maxTotal) {
            val y = bottom - chartH * v / maxTotal
            drawLine(DividerColor, Offset(chartLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.toString(), 0f, y + labelPx / 3, labelPaint)
            v += step
        }

        // One stacked bar per day that had games
        val slot = chartW / days.size
        val barW = minOf(slot * 0.72f, 18.dp.toPx())
        val labelInterval = ((days.size - 1) / 5 + 1).coerceAtLeast(1)
        days.forEachIndexed { i, d ->
            val cx = chartLeft + slot * i + slot / 2
            val totalH = chartH * d.total / maxTotal
            val authH = chartH * d.authorized / maxTotal
            val x = cx - barW / 2
            if (d.authorized > 0) {
                drawRect(
                    color = BarGreen,
                    topLeft = Offset(x, bottom - authH),
                    size = Size(barW, authH)
                )
            }
            val violations = d.violationDenied + d.violationNoTest
            if (violations > 0) {
                drawRect(
                    color = BarRed,
                    topLeft = Offset(x, bottom - totalH),
                    size = Size(barW, totalH - authH)
                )
            }
            if (i % labelInterval == 0 || i == days.size - 1) {
                val label = d.date.format(DAY_FMT)
                val tw = labelPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label, cx - tw / 2, h - 6.dp.toPx(), labelPaint
                )
            }
        }
    }
}

/**
 * Rating-over-time line chart for one pool, spanning the user's ENTIRE
 * history. [markers] draws dashed gold vertical lines with labels at the
 * given timestamps — currently the readiness system's adoption; later,
 * significant system changes can be added the same way.
 */
@Composable
private fun RatingHistoryChart(
    points: List<RatingHistoryPoint>,
    markers: List<Pair<Long, String>>,
    chartHeight: Dp = 150.dp,
    /** When set, tapping the chart opens the interactive landscape view. */
    onOpen: (() -> Unit)? = null
) {
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    val markerPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFD700")
        textSize = labelPx
        isAntiAlias = true
        isFakeBoldText = true
    }
    var canvasMod = Modifier.fillMaxWidth().height(chartHeight)
    if (onOpen != null) {
        canvasMod = canvasMod.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onOpen() }
    }
    Canvas(modifier = canvasMod) {
        if (points.size < 2) return@Canvas
        val padL = 38.dp.toPx()
        val padR = 10.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val tMin = points.first().endTimeMs.toFloat()
        val tMax = points.last().endTimeMs.toFloat()
        val tSpan = maxOf(1f, tMax - tMin)
        val rMin = points.minOf { it.rating }
        val rMax = points.maxOf { it.rating }
        val rPad = maxOf(4f, (rMax - rMin) * 0.08f)
        val lo = rMin - rPad
        val hi = rMax + rPad
        val rSpan = maxOf(1f, hi - lo)

        fun x(t: Long) = padL + chartW * ((t - tMin) / tSpan)
        fun y(r: Int) = padT + chartH * (1f - (r - lo) / rSpan)

        // Horizontal gridlines + rating labels
        val step = niceRatingStep(rMax - rMin)
        var v = ((lo.toInt() + step - 1) / step) * step
        while (v <= hi.toInt()) {
            val gy = y(v)
            drawLine(DividerColor, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.toString(), 0f, gy + labelPx / 3, labelPaint)
            v += step
        }

        // Rating line
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.endTimeMs), y(p.rating))
            else path.lineTo(x(p.endTimeMs), y(p.rating))
        }
        drawPath(path, BarGreen, style = Stroke(width = 2.dp.toPx()))

        // Event markers (readiness system adoption; extensible for future
        // significant system changes).
        markers.forEach { (ts, label) ->
            val mx = x(ts).coerceIn(padL, w - padR)
            val dash = 6.dp.toPx()
            val gap = 4.dp.toPx()
            var yy = padT
            while (yy < padT + chartH) {
                drawLine(
                    GoldValue, Offset(mx, yy),
                    Offset(mx, minOf(yy + dash, padT + chartH)),
                    strokeWidth = 2.dp.toPx()
                )
                yy += dash + gap
            }
            val tw = markerPaint.measureText(label)
            val tx = if (mx + tw + 8f > w) mx - tw - 6f else mx + 6f
            drawContext.canvas.nativeCanvas.drawText(label, tx, padT + labelPx, markerPaint)
        }

        // X-axis endpoint labels
        val firstLabel = formatDateShort(points.first().endTimeMs)
        drawContext.canvas.nativeCanvas.drawText(firstLabel, padL, h - 6.dp.toPx(), labelPaint)
        val lastLabel = formatDateShort(points.last().endTimeMs)
        drawContext.canvas.nativeCanvas.drawText(
            lastLabel, w - padR - labelPaint.measureText(lastLabel), h - 6.dp.toPx(), labelPaint
        )
    }
}

/**
 * Rating line since readiness-system adoption, with one clickable ◆
 * marker per registered system change ([ChessReadinessSystemChanges]).
 * Tapping near a marker opens a popup describing what changed and when.
 */
@Composable
private fun RatingSinceSystemChart(
    points: List<RatingHistoryPoint>,
    changes: List<ReadinessSystemChange>,
    systemStartMs: Long,
    chartHeight: Dp = 150.dp,
    /** Invoked when a tap does NOT hit a ◆ marker (opens the zoom view). */
    onOpenZoom: () -> Unit = {}
) {
    var selected by remember { mutableStateOf<ReadinessSystemChange?>(null) }
    var canvasWidth by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    val markerPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFD700")
        textSize = labelPx
        isAntiAlias = true
        isFakeBoldText = true
    }
    val padL = with(density) { 38.dp.toPx() }
    val padR = with(density) { 10.dp.toPx() }
    val tMin = points.firstOrNull()?.endTimeMs ?: 0L
    val tMax = points.lastOrNull()?.endTimeMs ?: 1L
    val tSpan = maxOf(1f, (tMax - tMin).toFloat())

    // Shared x-mapping so the drawn markers and the tap hit-test agree.
    fun markerX(ts: Long, w: Float): Float {
        val chartW = w - padL - padR
        return (padL + chartW * ((ts - tMin) / tSpan)).coerceIn(padL, w - padR)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(changes, canvasWidth) {
                detectTapGestures { pos ->
                    if (canvasWidth <= 0f) return@detectTapGestures
                    val threshold = with(density) { 24.dp.toPx() }
                    val nearest = changes.minByOrNull {
                        abs(markerX(it.timestampMs, canvasWidth) - pos.x)
                    } ?: return@detectTapGestures
                    if (abs(markerX(nearest.timestampMs, canvasWidth) - pos.x) <= threshold) {
                        selected = nearest
                    } else {
                        // Tap away from every marker → interactive zoom view.
                        onOpenZoom()
                    }
                }
            }
    ) {
        if (points.size < 2) return@Canvas
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val rMin = points.minOf { it.rating }
        val rMax = points.maxOf { it.rating }
        val rPad = maxOf(4f, (rMax - rMin) * 0.08f)
        val lo = rMin - rPad
        val hi = rMax + rPad
        val rSpan = maxOf(1f, hi - lo)

        fun x(t: Long) = padL + chartW * ((t - tMin) / tSpan)
        fun y(r: Int) = padT + chartH * (1f - (r - lo) / rSpan)

        // Horizontal gridlines + rating labels
        val step = niceRatingStep(rMax - rMin)
        var v = ((lo.toInt() + step - 1) / step) * step
        while (v <= hi.toInt()) {
            val gy = y(v)
            drawLine(DividerColor, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.toString(), 0f, gy + labelPx / 3, labelPaint)
            v += step
        }

        // Rating line — one segment per game, colored by that game's
        // authorization: green = played inside a valid GREEN window,
        // red = played without authorization (denied or no fresh test).
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            drawLine(
                color = if (to.authorized) BarGreen else BarRed,
                start = Offset(x(from.endTimeMs), y(from.rating)),
                end = Offset(x(to.endTimeMs), y(to.rating)),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Adoption start line (solid gold, labeled)
        val startX = markerX(systemStartMs, w)
        drawLine(GoldValue, Offset(startX, padT), Offset(startX, padT + chartH), strokeWidth = 2.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText("♟ start", startX + 6f, padT + labelPx, markerPaint)

        // Clickable ◆ markers, one per registered system change
        changes.forEach { c ->
            val mx = markerX(c.timestampMs, w)
            val cy = padT + 10.dp.toPx()
            val r = 5.dp.toPx()
            val diamond = Path().apply {
                moveTo(mx, cy - r)
                lineTo(mx + r, cy)
                lineTo(mx, cy + r)
                lineTo(mx - r, cy)
                close()
            }
            drawPath(diamond, GoldValue)
            // Faint guide line down to the timeline so the change point
            // is easy to locate.
            drawLine(
                GoldValue.copy(alpha = 0.45f),
                Offset(mx, cy + r),
                Offset(mx, padT + chartH),
                strokeWidth = 1f
            )
        }

        // X-axis endpoint labels
        val firstLabel = formatDateShort(points.first().endTimeMs)
        drawContext.canvas.nativeCanvas.drawText(firstLabel, padL, h - 6.dp.toPx(), labelPaint)
        val lastLabel = formatDateShort(points.last().endTimeMs)
        drawContext.canvas.nativeCanvas.drawText(
            lastLabel, w - padR - labelPaint.measureText(lastLabel), h - 6.dp.toPx(), labelPaint
        )
    }

    selected?.let { change ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close", color = LinkColor, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text(
                    change.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Text(
                    ChessReadinessSystemChanges.dateLabel(change) + "\n\n" + change.description,
                    color = LabelColor,
                    fontSize = 13.sp
                )
            },
            containerColor = SectionBg
        )
    }
}

private fun niceRatingStep(range: Int): Int {
    val target = maxOf(1, range / 4)
    return listOf(10, 25, 50, 100, 200, 400).firstOrNull { it >= target } ?: 400
}

private fun formatDateShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM yy"))

/**
 * Diverging-bar chart of average rating delta per game: for each rating
 * pool, the green bar is the authorized average and the red bar the
 * violation average; both extend from the center axis — right for gains,
 * left for losses.
 */
@Composable
private fun RatingDeltaChart(
    pools: List<RatingPoolStats>,
    rowHeightDp: Int = 46
) {
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val poolPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#ADD8E6")
        textSize = labelPx
        isAntiAlias = true
    }
    val valPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    Canvas(modifier = Modifier.fillMaxWidth().height((pools.size * rowHeightDp + 8).dp)) {
        val rowH = rowHeightDp.dp.toPx()
        val pad = 6.dp.toPx()
        val w = size.width
        val axisX = w / 2
        val halfW = w / 2 - pad
        val maxAbs = maxOf(
            1f,
            pools.maxOf {
                maxOf(abs(it.authorized.avgDelta), abs(it.violations.avgDelta)).toFloat()
            }
        )
        val pxPerPt = halfW / maxAbs

        // Center axis
        drawLine(DividerColor, Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 1f)

        pools.forEachIndexed { i, p ->
            val rowTop = i * rowH + 4.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(p.label, 0f, rowTop + labelPx, poolPaint)

            val bar1Y = rowTop + 16.dp.toPx()
            val bar2Y = rowTop + 30.dp.toPx()
            val barH = 8.dp.toPx()
            listOf(
                Triple(p.authorized.avgDelta, bar1Y, BarGreen),
                Triple(p.violations.avgDelta, bar2Y, BarRed)
            ).forEach { (delta, y, color) ->
                val len = (abs(delta).toFloat() * pxPerPt).coerceAtMost(halfW)
                val x0 = if (delta >= 0) axisX else axisX - len
                drawRect(color, topLeft = Offset(x0, y), size = Size(len, barH))
                val txt = "%+.1f".format(delta)
                val tw = valPaint.measureText(txt)
                val tx = if (delta >= 0) x0 + len + 4f else x0 - 4f - tw
                drawContext.canvas.nativeCanvas.drawText(
                    txt, tx, y + barH / 2 + labelPx / 3, valPaint
                )
            }
        }
    }
}

private fun fmtRatingDelta(c: com.example.tail.data.RatingCategoryStats): String =
    if (c.games == 0) {
        "no games"
    } else {
        "%+d total · %+.1f/game · %d games · %.0f%% win".format(
            c.totalDelta, c.avgDelta, c.games, c.winRate
        )
    }

@Composable
private fun ComplianceLegend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LegendSwatch(BarGreen, "Authorized")
        LegendSwatch(BarRed, "Violation (denied / no fresh test)")
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = DimColor, fontSize = 10.sp)
    }
}

package com.example.tail.widget

import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness V3 — Reflex + Puzzle Rush Survival Gate
 * ════════════════════════════════════════════════════════════════════════
 *
 * A combination of the v1 and v2 systems with a new two-step pipeline:
 *
 *  Step 1 — 2-MINUTE REFLEX TEST (PVT-B, shortened)
 *      A hard physiological filter. If systemic CNS fatigue, severe sleep
 *      debt or excessive reaction latency/attentional lapses are present,
 *      calculation capacity is already compromised — the run ends here in
 *      a TOTAL REST LOCKOUT. Reuses the v2 PVT mechanics (random ISI
 *      2–10 s, 355 ms lapse threshold, <100 ms false starts) but lasts
 *      only [REFLEX_DURATION_MS] and is scored BINARY (pass/fail), not on
 *      the v2 tier ladder.
 *
 *  Step 2 — CHESS SURVIVAL TACTICAL GATE (Puzzle Rush Survival)
 *      A domain-specific cognitive + inhibitory control gate. The user
 *      solves real puzzles in the chess app underneath the overlay and
 *      self-reports each solve:
 *        ✓ PASS   — logs the puzzle as solved (with latency), advances
 *                    X → X + 1; reaching the dynamic target passes;
 *        ✕ STRIKE — zero-tolerance: any failure instantly fails the run;
 *        TIMEOUT  — a global 5-minute cap auto-fails (calculation
 *                    sluggishness / cognitive drain).
 *
 *  Dynamic target:  the user picks the chess type they are about to play
 *  (bullet / blitz / rapid / chess960) and the target scales with their
 *  CURRENT rating in that variant — see [targetFromRating]. The old
 *  PB-based target ([targetScore], round(PB × 0.60)) remains the FALLBACK
 *  when no rating is known, so pushing an untimed all-time survival high
 *  no longer raises the bar for the timed gate.
 *
 *  Verdict mapping into the SHARED v1 traffic-light system (so Chess
 *  Guard enforcement, the color system and the Phase-2 audit pipeline
 *  keep working unchanged):
 *      PASS         → GREEN_LIGHT  (rated play unlocked)
 *      FAIL_STRIKE  → YELLOW_LIGHT (near-miss: casual play allowed,
 *                      rated locked — a single wrong puzzle is a bad
 *                      day at the board, not a systemic red flag)
 *      FAIL_TIMEOUT → RED_LIGHT (rated play locked out)
 *      FAIL_REFLEX  → RED_LIGHT (total rest lockout — worst ccrs)
 *
 * Pure Kotlin (java.time only) — no Android dependencies, unit-testable.
 */
object ChessReadinessV3Engine {

    // ── Step 1: reflex (2-minute PVT-B) ────────────────────────────────────

    /** Reflex test duration (ms) — 2 minutes, down from v2's 3. */
    const val REFLEX_DURATION_MS = 2L * 60 * 1000

    /**
     * Reflex failure thresholds, scaled from the v2 3-minute matrix
     * (5 lapses / 4 false starts) to the shorter 2-minute run. At or
     * above EITHER threshold the nervous system fails the check →
     * total rest lockout.
     */
    const val REFLEX_FAIL_LAPSES = 3
    const val REFLEX_FAIL_FALSE_STARTS = 3

    /** One reflex run's aggregated outcome (from the v2 PVT summarizer). */
    data class ReflexSummary(
        val lapses: Int,
        val falseStarts: Int,
        val meanRtMs: Double?,
        val passed: Boolean
    )

    /** Binary reflex verdict from a completed 2-minute PVT-B summary. */
    fun reflexPassed(lapses: Int, falseStarts: Int): Boolean =
        lapses < REFLEX_FAIL_LAPSES && falseStarts < REFLEX_FAIL_FALSE_STARTS

    fun summarizeReflex(samples: List<ChessReadinessV2Engine.PvtSample>): ReflexSummary {
        val s = ChessReadinessV2Engine.summarizePvt(samples)
        return ReflexSummary(
            lapses = s.lapses,
            falseStarts = s.falseStarts,
            meanRtMs = s.meanRtMs,
            passed = reflexPassed(s.lapses, s.falseStarts)
        )
    }

    // ── Step 2: puzzle rush survival gate ──────────────────────────────────

    /**
     * Rating-derived survival target: round((rating − 700) / 60), clamped
     * to 8..28 puzzles inside the 5-minute cap. Examples: 812 → 8,
     * 926 → 11, 1113 → 17, 1200 → 20, 1500+ → 28 (capped). A higher current
     * rating means a harder pre-game gate — ~1 extra puzzle per 30 rating
     * points above 600.
     */
    fun targetFromRating(rating: Int): Int {
        if (rating <= 0) return targetScore(DEFAULT_PB)
        return Math.round((rating - 600) / 30.0).toInt().coerceIn(8, 28)
    }

    /**
     * Hard minimum: below this the run can NEVER pass, regardless of
     * percentile history — 60% of the guaranteed target.
     */
    fun floorTarget(target: Int): Int =
        kotlin.math.ceil(target * 0.60).toInt().coerceAtLeast(1)

    /**
     * The pass bar actually enforced during a run. The rating-derived
     * [target] is the GUARANTEED-play number; but a run that lands below it
     * can still pass when it matches the user's own experience — the 70th
     * percentile of their past solved counts ([percentileTarget]) — as long
     * as it clears the hard [floorTarget] minimum. With no percentile
     * history the guaranteed target is the only bar.
     */
    fun effectivePassTarget(target: Int, pastPuzzlesPassed: List<Int>): Int {
        val pct = percentileTarget(pastPuzzlesPassed) ?: return target
        return maxOf(floorTarget(target), minOf(target, pct))
    }

    /** Global survival session cap (ms) — exceeding it auto-fails. */
    const val SURVIVAL_CAP_MS = 5L * 60 * 1000

    /** target = round(PB × this ratio). */
    const val TARGET_RATIO = 0.60

    /**
     * DUAL WIN CONDITION — percentile win. Reaching the 70th percentile of
     * the user's own past survival performance ALSO wins the gate, while the
     * 60%-of-PB absolute target stays the fallback/absolute bar. The run
     * always continues up to the absolute target even after the percentile
     * win is secured (the percentile threshold never terminates the run).
     */
    const val PERCENTILE_WIN = 70

    /**
     * Past-run window for the percentile computation — mirrors the v2 reflex
     * speed baseline (SPEED_BASELINE_WINDOW / MIN_SPEED_BASELINE_SAMPLES):
     * last 30 results, active only once at least 8 exist.
     */
    const val PERCENTILE_WINDOW = 30
    const val MIN_PERCENTILE_SAMPLES = 8

    /** Fallback PB when nothing is configured yet (keeps the gate usable). */
    const val DEFAULT_PB = 25

    /** Floor for the computed target so a tiny PB still asks something. */
    const val MIN_TARGET = 5

    /**
     * Dynamic target: round(pb × 0.60). A missing/invalid PB (≤ 0) falls
     * back to [DEFAULT_PB] so the gate always has a concrete bar.
     */
    fun targetScore(pb: Int): Int {
        val effective = if (pb > 0) pb else DEFAULT_PB
        return (effective * TARGET_RATIO).roundToInt().coerceAtLeast(MIN_TARGET)
    }

    /** Final verdict of a v3 run (also the telemetry `final_verdict`). */
    enum class Verdict {
        PASS, FAIL_REFLEX, FAIL_STRIKE, FAIL_TIMEOUT
    }

    /** One logged survival puzzle event (telemetry). */
    data class SurvivalEvent(
        val puzzleIndex: Int,
        val puzzleDurationMs: Long,
        val timestamp: Long,
        val verdict: Verdict
    )

    /**
     * Gatekeeper transition for one PASS click.
     *  - puzzlesPassed + 1 < target  → still RUNNING;
     *  - puzzlesPassed + 1 == target → GATE PASSED.
     * (Strike clicks and the timeout never route through here — both are
     * immediate terminal failures by design.)
     */
    fun onPass(puzzlesPassed: Int, target: Int): Boolean =
        puzzlesPassed + 1 >= target

    /**
     * 70th-percentile (nearest-rank, rounded up) of past runs' solved-puzzle
     * counts. Null until [MIN_PERCENTILE_SAMPLES] past results exist — with
     * less history the percentile win is simply inactive and only the
     * absolute 60%-of-PB target applies. Windowed to the last
     * [PERCENTILE_WINDOW] results, like the v2 reflex speed baseline.
     */
    fun percentileTarget(pastPuzzlesPassed: List<Int>): Int? {
        if (pastPuzzlesPassed.size < MIN_PERCENTILE_SAMPLES) return null
        val window = pastPuzzlesPassed.takeLast(PERCENTILE_WINDOW).sorted()
        val rank = kotlin.math.ceil(PERCENTILE_WIN / 100.0 * window.size).toInt()
            .coerceIn(1, window.size)
        return window[rank - 1].coerceAtLeast(1)
    }

    /** Whether the percentile win threshold has been reached this run. */
    fun percentileReached(puzzlesPassed: Int, pctTarget: Int?): Boolean =
        pctTarget != null && puzzlesPassed >= pctTarget

    /** Whether the global cap has been exceeded at [elapsedMs]. */
    fun timedOut(elapsedMs: Long): Boolean = elapsedMs >= SURVIVAL_CAP_MS

    // ── Verdict → shared v1 system mapping ─────────────────────────────────

    /** v1-compatible traffic-light state name (what Chess Guard reads). */
    fun stateNameFor(verdict: Verdict): String = when (verdict) {
        Verdict.PASS -> ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name
        // A single strike is a near-miss → YELLOW (casual play continues,
        // rated locked). Timeout and reflex failure are systemic → RED.
        Verdict.FAIL_STRIKE -> ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name
        Verdict.FAIL_REFLEX,
        Verdict.FAIL_TIMEOUT -> ChessReadinessEngine.ReadinessState.RED_LIGHT.name
    }

    /**
     * Synthetic CCRS for the SHARED history record (drives the rest-period
     * ladder for failed tests: < 40 → 120 min, 40–59 → 60 min). The reflex
     * failure is the most severe (total rest). A strike is a YELLOW
     * near-miss — its 65 keeps it in the standard 60-min cool-down band
     * (same as a passed session) instead of the 120-min severe rest ladder.
     */
    fun syntheticCcrs(verdict: Verdict): Int = when (verdict) {
        Verdict.PASS -> 85
        Verdict.FAIL_STRIKE -> 65
        Verdict.FAIL_TIMEOUT -> 40
        Verdict.FAIL_REFLEX -> 20
    }
}

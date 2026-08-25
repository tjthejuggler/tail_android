package com.example.tail.widget

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness V2 — Cognitive Readiness Gating Engine
 * ════════════════════════════════════════════════════════════════════════
 *
 * Replaces the v1 Phase-1 diagnostic (CCRS sleep/clarity/puzzle survey) with
 * an objective neurobiological gate, per the research framework:
 *
 *  Module 1 — AUTONOMIC (overnight, passive)
 *      lnRMSSD (natural log of the nightly RMSSD in ms) and resting heart
 *      rate, each judged as an intra-individual Z-score: a 7-day EWMA
 *      "acute" value vs the rolling 30-day baseline (μ30, σ30). lnRMSSD is
 *      gated ASYMMETRICALLY: suppression gates (−0.5 → Tier 2, ≤ −1.5 →
 *      Tier 3), while ELEVATION is informational up to +1.5 and caps at
 *      Tier 2 — a rising HRV is usually recovery (e.g. returning to a
 *      pre-injury level the rolling μ30 no longer represents), and only
 *      large spikes (the weakly-evidenced pre-illness "paradoxical surge")
 *      warrant caution, never a lockout. RHR gates only when ELEVATED.
 *
 *  Module 2 — CNS VIGILANCE (3-minute PVT-B)
 *      Lapse = any response ≥ 355 ms (the empirically re-calibrated 10-min
 *      → 3-min threshold). False start = a response before the stimulus or
 *      within 100 ms of it (physiologically impossible → anticipation).
 *      Response speed is the reciprocal transform 1000 / RT_ms.
 *
 *  Module 2b — PERSONAL SPEED BASELINE (Tier-1 performance gate)
 *      Once ≥ 8 past CLEAN runs (zero early taps) exist, a run whose
 *      response speed lands in the personal bottom 30% (percentile rank
 *      vs the user's own rolling clean-run baseline, ±2 h circadian
 *      matching when enough same-hour samples exist) is demoted Tier 1 →
 *      Tier 2 — "play rated only when relatively at your best". It can
 *      NEVER produce a Tier 3: absolute population thresholds remain the
 *      only lockout path, so chronic baseline drift is still caught.
 *
 *  Module 3 — CUMULATIVE LOAD (cognitive ACWR, EWMA model)
 *      Daily cognitive training impulse cTRIMP = minutes × intensity
 *      (sRPE-equivalent). EWMA acute λ = 2/(7+1) = 0.25, chronic
 *      λ = 2/(28+1). Sweet spot 0.8–1.3; danger > 1.5. NOTE: the LOW side
 *      (< 0.8, "detraining") does NOT gate — see [workloadTier].
 *
 *  GATING MATRIX (immutable, logical OR down the tiers — the WORST module
 *  wins):
 *      Tier 1 PEAK:   Z_lnRMSSD ≥ −0.5 (elevation < +1.5 informational)
 *                     AND Z_RHR ≤ +0.5
 *                     AND lapses ≤ 1 AND false starts ≤ 1
 *                     AND ACWR ≤ 1.3 (low ACWR is informational only)
 *      Tier 3 LOCKOUT: Z_lnRMSSD ≤ −1.5 OR Z_RHR ≥ +1.5
 *                      OR lapses ≥ 5 OR false starts ≥ 4 OR ACWR > 1.5
 *                      (a lnRMSSD SPIKE ≥ +1.5 is Tier 2 only — never lockout)
 *      Tier 2 RESTRICTED: everything else.
 *
 *  Tier 1 → GREEN (rated play), Tier 2 → YELLOW (unrated/study only),
 *  Tier 3 → RED (complete lockout) — the exact v1 session states, so the
 *  Chess Guard enforcement and the Phase-2 audit pipeline keep working
 *  unchanged for both versions.
 *
 *  A module with insufficient data (no Garmin biometrics yet, < 7 baseline
 *  days, < 14 days of load history) is NO_DATA and does NOT gate — the
 *  remaining modules decide. This keeps the system usable on day one.
 *
 * Pure Kotlin (java.time only) — no Android dependencies, unit-testable.
 */
object ChessReadinessV2Engine {

    // ── Constants ──────────────────────────────────────────────────────────

    /** PVT-B duration (ms). */
    const val PVT_DURATION_MS = 3L * 60 * 1000

    /** Random inter-stimulus interval bounds (ms), per the PVT-B spec. */
    const val ISI_MIN_MS = 2_000L
    const val ISI_MAX_MS = 10_000L

    /** Lapse threshold for the 3-minute PVT-B (ms). */
    const val LAPSE_THRESHOLD_MS = 355

    /** Responses faster than this (ms) are physiologically impossible. */
    const val FALSE_START_THRESHOLD_MS = 100

    /** Baseline window length (days, excluding today). */
    const val BASELINE_WINDOW_DAYS = 30

    /** Acute EWMA window (days) for the biometric Z-scores. */
    const val ACUTE_WINDOW_DAYS = 7

    /** λ for the 7-day acute EWMA (biometrics and ACWR acute load). */
    const val LAMBDA_ACUTE = 2.0 / (ACUTE_WINDOW_DAYS + 1)          // 0.25

    /** λ for the 28-day chronic EWMA of the cognitive ACWR. */
    const val LAMBDA_CHRONIC = 2.0 / (28.0 + 1.0)                   // ≈ 0.0714

    /** Minimum baseline samples before the autonomic module gates. */
    const val MIN_BASELINE_SAMPLES = 7

    /** Minimum distinct days of load history before the ACWR module gates. */
    const val MIN_ACWR_HISTORY_DAYS = 14

    /** σ floors — below these, day-to-day noise is pure measurement noise. */
    const val SD_FLOOR_LNRMSSD = 0.05
    const val SD_FLOOR_RHR = 0.5

    /** SWC (Smallest Worthwhile Change) = 0.5 σ — the Tier-1 band half-width. */
    const val SWC_Z = 0.5

    /** ACWR bands. */
    const val ACWR_SWEET_LOW = 0.8
    const val ACWR_SWEET_HIGH = 1.3
    const val ACWR_DANGER = 1.5

    /** cTRIMP intensity (sRPE-equivalent 1–10) by game class. */
    const val INTENSITY_RAPID = 8.0
    const val INTENSITY_BLITZ = 6.0
    const val INTENSITY_BULLET = 5.0

    /** Rated play costs one extra intensity point (stakes → strain). */
    const val RATED_INTENSITY_BONUS = 1.0

    /** The v2 test session itself (PVT ≈ 3 min) as cTRIMP minutes×intensity. */
    const val TEST_SESSION_MINUTES = 3.0
    const val TEST_SESSION_INTENSITY = 4.0

    // ── Personal speed baseline (Module 2b — Tier-1 performance gate) ──────

    /** Past CLEAN runs (0 early taps) considered for the personal speed baseline. */
    const val SPEED_BASELINE_WINDOW = 30

    /** Clean runs required before the personal speed gate activates. */
    const val MIN_SPEED_BASELINE_SAMPLES = 8

    /** Circadian matching: baseline runs within ±this many hours of the test. */
    const val SPEED_BASELINE_HOUR_WINDOW_H = 2

    /** Hour-matched samples required to prefer the same-hour subset. */
    const val MIN_SPEED_HOUR_SAMPLES = 4

    /** Percentile rank at/below which the run is "personal bottom" (demotes 1 → 2). */
    const val SPEED_DEMOTE_PERCENTILE = 30.0

    // ── Tier model ─────────────────────────────────────────────────────────

    /** Access tier; NO_DATA never gates (a missing module is not a failure). */
    enum class V2Tier {
        TIER1_PEAK, TIER2_RESTRICTED, TIER3_LOCKOUT, NO_DATA;

        /** Worst-of across modules: the gating matrix's OR-down-the-tiers. */
        fun worstOf(other: V2Tier): V2Tier = when {
            this == TIER3_LOCKOUT || other == TIER3_LOCKOUT -> TIER3_LOCKOUT
            this == TIER2_RESTRICTED || other == TIER2_RESTRICTED -> TIER2_RESTRICTED
            this == TIER1_PEAK && other == TIER1_PEAK -> TIER1_PEAK
            this == NO_DATA -> other
            other == NO_DATA -> this
            else -> TIER2_RESTRICTED
        }
    }

    // ── Module 1: Autonomic Z-scores ───────────────────────────────────────

    /** One day of overnight biometrics (either field may be null). */
    data class DailyBiometric(
        /** ISO date the values belong to (the sleep session's morning). */
        val date: LocalDate,
        /** Overnight RMSSD in milliseconds (Garmin HRV "last night"). */
        val rmssdMs: Int?,
        /** Resting heart rate in bpm. */
        val restingHr: Int?
    )

    /** Natural-log transform of a raw RMSSD value (ms). */
    fun lnRmssd(rmssdMs: Int): Double = ln(rmssdMs.toDouble())

    /**
     * Iterative EWMA over chronological values with decay [lambda]:
     * e_0 = x_0;  e_t = λ·x_t + (1−λ)·e_{t−1}.
     * Empty input → null.
     */
    fun ewma(values: List<Double>, lambda: Double): Double? {
        if (values.isEmpty()) return null
        var e = values.first()
        for (i in 1 until values.size) e = lambda * values[i] + (1 - lambda) * e
        return e
    }

    /** mean / sample-stddev / n of a non-empty sample (null when empty). */
    data class BaselineStats(val mean: Double, val sd: Double, val n: Int)

    fun baselineStats(values: List<Double>): BaselineStats? {
        if (values.size < 2) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return BaselineStats(mean, sqrt(variance), values.size)
    }

    /** The autonomic module's verdict for one evaluation. */
    data class AutonomicEvaluation(
        /** Z of the 7-day EWMA acute lnRMSSD vs the 30-day baseline (null = no data). */
        val zLnRmssd: Double?,
        /** Z of the 7-day EWMA acute RHR vs the 30-day baseline (null = no data). */
        val zRhr: Double?,
        /** Baseline lnRMSSD μ30 (for display). */
        val baselineLnRmssdMean: Double?,
        /** Baseline RHR μ30 (for display). */
        val baselineRhrMean: Double?,
        /** Acute (7-day EWMA) lnRMSSD (for display). */
        val acuteLnRmssd: Double?,
        /** Acute (7-day EWMA) RHR (for display). */
        val acuteRhr: Double?,
        /** Baseline sample count behind the lnRMSSD Z. */
        val lnRmssdSamples: Int,
        /** Baseline sample count behind the RHR Z. */
        val rhrSamples: Int,
        /** Tier of this module alone. */
        val tier: V2Tier
    )

    /**
     * Computes the autonomic Z-scores for [today]:
     *  - baseline = values in the window [today − 30, today − 1];
     *  - acute = 7-day EWMA (λ = 0.25) over values in [today − 6, today]
     *    (today's overnight reading included when present);
     *  - Z = (acute − μ30) / max(σ30, floor).
     *
     * Fewer than [MIN_BASELINE_SAMPLES] baseline values (or no acute value)
     * → that metric is NO_DATA and does not gate.
     */
    fun evaluateAutonomic(
        history: List<DailyBiometric>,
        today: LocalDate
    ): AutonomicEvaluation {
        val baselineStart = today.minusDays(BASELINE_WINDOW_DAYS.toLong())
        val baselineEnd = today.minusDays(1)
        val acuteStart = today.minusDays((ACUTE_WINDOW_DAYS - 1).toLong())

        val baselineWindow = history.filter { it.date in baselineStart..baselineEnd }
        val acuteWindow = history.filter { it.date >= acuteStart && it.date <= today }

        // lnRMSSD — log-transformed BEFORE any statistics (skew removal).
        val lnBaseline = baselineWindow.mapNotNull { b ->
            b.rmssdMs?.takeIf { it > 0 }?.let { lnRmssd(it) }
        }
        val lnAcute = acuteWindow.mapNotNull { b ->
            b.rmssdMs?.takeIf { it > 0 }?.let { lnRmssd(it) }
        }
        val lnStats = baselineStats(lnBaseline)
        val lnAcuteEwma = ewma(lnAcute, LAMBDA_ACUTE)
        val zLn = if (lnStats != null && lnAcuteEwma != null && lnStats.n >= MIN_BASELINE_SAMPLES) {
            (lnAcuteEwma - lnStats.mean) / maxOf(lnStats.sd, SD_FLOOR_LNRMSSD)
        } else null

        // RHR — raw bpm, one-sided gating (only ELEVATION is penalized).
        val rhrBaseline = baselineWindow.mapNotNull { b -> b.restingHr?.takeIf { it > 0 }?.toDouble() }
        val rhrAcute = acuteWindow.mapNotNull { b -> b.restingHr?.takeIf { it > 0 }?.toDouble() }
        val rhrStats = baselineStats(rhrBaseline)
        val rhrAcuteEwma = ewma(rhrAcute, LAMBDA_ACUTE)
        val zRhr = if (rhrStats != null && rhrAcuteEwma != null && rhrStats.n >= MIN_BASELINE_SAMPLES) {
            (rhrAcuteEwma - rhrStats.mean) / maxOf(rhrStats.sd, SD_FLOOR_RHR)
        } else null

        return AutonomicEvaluation(
            zLnRmssd = zLn,
            zRhr = zRhr,
            baselineLnRmssdMean = lnStats?.mean,
            baselineRhrMean = rhrStats?.mean,
            acuteLnRmssd = lnAcuteEwma,
            acuteRhr = rhrAcuteEwma,
            lnRmssdSamples = lnStats?.n ?: 0,
            rhrSamples = rhrStats?.n ?: 0,
            tier = autonomicTier(zLn, zRhr)
        )
    }

    /**
     * Autonomic tier per the matrix. lnRMSSD is ASYMMETRIC: suppression
     * gates (−0.5 → Tier 2, ≤ −1.5 → Tier 3) but elevation is
     * informational up to +1.5 and caps at Tier 2 — HRV rising back to a
     * pre-injury baseline must not gate play, while a large spike (the
     * weakly-evidenced pre-illness "paradoxical surge") still cautions.
     * RHR is one-sided (only +Z gates); NO_DATA metrics never gate.
     */
    fun autonomicTier(zLnRmssd: Double?, zRhr: Double?): V2Tier {
        var tier = V2Tier.TIER1_PEAK
        if (zLnRmssd != null) {
            val z = zLnRmssd
            tier = tier.worstOf(
                when {
                    z <= -1.5 -> V2Tier.TIER3_LOCKOUT      // severe suppression
                    z < -0.5 -> V2Tier.TIER2_RESTRICTED     // moderate suppression
                    z >= 1.5 -> V2Tier.TIER2_RESTRICTED     // large spike: caution, never lockout
                    else -> V2Tier.TIER1_PEAK               // −0.5…+1.5 (elevation informational)
                }
            )
        }
        if (zRhr != null) {
            tier = tier.worstOf(
                when {
                    zRhr >= 1.5 -> V2Tier.TIER3_LOCKOUT
                    zRhr > 0.5 -> V2Tier.TIER2_RESTRICTED
                    else -> V2Tier.TIER1_PEAK
                }
            )
        }
        return tier
    }

    // ── Module 2: PVT-B ────────────────────────────────────────────────────

    /**
     * One PVT-B trial. [rtMs] is the measured reaction time for a real
     * stimulus response; null means a FALSE START (tapped before the
     * stimulus, or within [FALSE_START_THRESHOLD_MS] of it — both are
     * anticipatory, no valid RT exists).
     */
    data class PvtSample(val rtMs: Int?)

    /** Aggregated PVT-B outcome. */
    data class PvtSummary(
        /** Number of valid stimulus responses. */
        val validResponses: Int,
        /** Responses ≥ [LAPSE_THRESHOLD_MS] — attentional lapses. */
        val lapses: Int,
        /** Anticipatory responses (before stimulus or < 100 ms). */
        val falseStarts: Int,
        /** Mean reciprocal response time 1000/RT over valid responses (null if none). */
        val meanRrt: Double?,
        /** Mean raw RT over valid responses, ms (null if none). */
        val meanRtMs: Double?,
        /** Slowest valid RT, ms (null if none). */
        val maxRtMs: Int?,
        /** Tier of this module alone. */
        val tier: V2Tier
    )

    /**
     * Classifies one raw response:
     *  - [FalseStart] when tapped before the stimulus appeared (rt < 0)
     *    or within 100 ms of it;
     *  - [Lapse] when RT ≥ 355 ms;
     *  - [Valid] otherwise.
     */
    sealed class PvtClassification {
        object Valid : PvtClassification()
        object Lapse : PvtClassification()
        object FalseStart : PvtClassification()
    }

    fun classifyPvtResponse(rtMs: Int?): PvtClassification = when {
        rtMs == null || rtMs < FALSE_START_THRESHOLD_MS -> PvtClassification.FalseStart
        rtMs >= LAPSE_THRESHOLD_MS -> PvtClassification.Lapse
        else -> PvtClassification.Valid
    }

    /** Aggregates samples into the PVT-B summary + module tier. */
    fun summarizePvt(samples: List<PvtSample>): PvtSummary {
        var valid = 0
        var lapses = 0
        var falseStarts = 0
        val rts = ArrayList<Int>(samples.size)
        for (s in samples) {
            when (classifyPvtResponse(s.rtMs)) {
                PvtClassification.FalseStart -> falseStarts++
                PvtClassification.Lapse -> { lapses++; valid++; rts.add(s.rtMs!!) }
                PvtClassification.Valid -> { valid++; rts.add(s.rtMs!!) }
            }
        }
        return PvtSummary(
            validResponses = valid,
            lapses = lapses,
            falseStarts = falseStarts,
            meanRrt = if (rts.isEmpty()) null else rts.average().let { _ -> rts.map { 1000.0 / it }.average() },
            meanRtMs = if (rts.isEmpty()) null else rts.average(),
            maxRtMs = rts.maxOrNull(),
            tier = pvtTier(lapses, falseStarts)
        )
    }

    /** PVT-B tier per the matrix (lapses ≥ 5 OR false starts ≥ 4 → Tier 3). */
    fun pvtTier(lapses: Int, falseStarts: Int): V2Tier = when {
        lapses >= 5 || falseStarts >= 4 -> V2Tier.TIER3_LOCKOUT
        lapses >= 2 || falseStarts >= 2 -> V2Tier.TIER2_RESTRICTED
        else -> V2Tier.TIER1_PEAK
    }

    // ── Module 2b: Personal speed baseline (Tier-1 performance gate) ───────

    /**
     * One PAST clean PVT run (zero early taps) feeding the personal speed
     * baseline. The caller EXCLUDES the run currently being judged.
     */
    data class SpeedSample(
        val timestampMs: Long,
        /** Mean reciprocal response speed 1000/RT (higher = faster). */
        val meanRrt: Double
    )

    /** The personal speed-baseline verdict for the run being judged. */
    data class SpeedBaselineEvaluation(
        /** True when enough clean-run history exists to gate. */
        val active: Boolean,
        /** Percentile rank of this run's speed among the baseline (0–100, higher = faster). */
        val percentile: Double?,
        /** Clean runs the percentile was computed against. */
        val baselineN: Int,
        /** True when a ±2 h same-hour subset was used (circadian matching). */
        val hourMatched: Boolean,
        /** Baseline median speed (for display). */
        val baselineMedian: Double?,
        /** True when the run sits in the personal bottom band → Tier 1 demoted to Tier 2. */
        val demotesTier1: Boolean
    )

    /**
     * Judges this run's response speed against the user's OWN rolling
     * baseline of clean runs — the "play rated only when relatively at
     * your best" gate:
     *  - baseline = the last [SPEED_BASELINE_WINDOW] clean runs BEFORE this
     *    one (the caller excludes the current run);
     *  - inactive below [MIN_SPEED_BASELINE_SAMPLES] samples — until then
     *    the absolute bands alone decide Tier 1;
     *  - circadian matching: when ≥ [MIN_SPEED_HOUR_SAMPLES] baseline runs
     *    sit within ±[SPEED_BASELINE_HOUR_WINDOW_H] h of this test, only
     *    those are used (PVT speed swings 10–20 % across the day);
     *  - percentile = share of baseline runs SLOWER than this one (ties
     *    count in the user's favour);
     *  - demotes Tier 1 → Tier 2 at/below [SPEED_DEMOTE_PERCENTILE]; NEVER
     *    adds a Tier 3 — absolute population thresholds remain the only
     *    lockout path, so chronic baseline drift is still caught.
     */
    fun evaluateSpeedBaseline(
        baseline: List<SpeedSample>,
        currentRrt: Double?,
        currentTimestampMs: Long?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): SpeedBaselineEvaluation {
        val windowed = baseline.sortedBy { it.timestampMs }.takeLast(SPEED_BASELINE_WINDOW)
        if (currentRrt == null || windowed.size < MIN_SPEED_BASELINE_SAMPLES) {
            return SpeedBaselineEvaluation(
                active = false,
                percentile = null,
                baselineN = windowed.size,
                hourMatched = false,
                baselineMedian = windowed.map { it.meanRrt }.medianOrNull(),
                demotesTier1 = false
            )
        }
        var pool = windowed
        var hourMatched = false
        if (currentTimestampMs != null) {
            val curHour = java.time.Instant.ofEpochMilli(currentTimestampMs).atZone(zone).hour
            val sameHour = windowed.filter { s ->
                val h = java.time.Instant.ofEpochMilli(s.timestampMs).atZone(zone).hour
                val d = abs(h - curHour)
                val circular = minOf(d, 24 - d)
                circular <= SPEED_BASELINE_HOUR_WINDOW_H
            }
            if (sameHour.size >= MIN_SPEED_HOUR_SAMPLES) {
                pool = sameHour
                hourMatched = true
            }
        }
        val percentile = pool.count { it.meanRrt < currentRrt } * 100.0 / pool.size
        return SpeedBaselineEvaluation(
            active = true,
            percentile = percentile,
            baselineN = pool.size,
            hourMatched = hourMatched,
            baselineMedian = pool.map { it.meanRrt }.medianOrNull(),
            demotesTier1 = percentile <= SPEED_DEMOTE_PERCENTILE
        )
    }

    /** Median of a non-empty double list (null when empty). */
    private fun List<Double>.medianOrNull(): Double? =
        if (isEmpty()) null else sorted().let {
            if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
        }

    // ── Module 3: Cognitive ACWR (EWMA) ────────────────────────────────────

    /** One chess.com game reduced to its load inputs. */
    data class GameLoad(
        /** Epoch millis when the game ended. */
        val endTimeMs: Long,
        /** Estimated minutes played (base clock). */
        val minutes: Double,
        /** Classified type name ("BULLET" / "BLITZ" / "RAPID"). */
        val type: String,
        /** Whether the game affected ratings. */
        val rated: Boolean
    )

    /** sRPE-equivalent intensity multiplier for one game. */
    fun gameIntensity(type: String, rated: Boolean): Double {
        val base = when (type.uppercase()) {
            "RAPID" -> INTENSITY_RAPID
            "BLITZ" -> INTENSITY_BLITZ
            "BULLET" -> INTENSITY_BULLET
            else -> INTENSITY_BLITZ
        }
        return base + if (rated) RATED_INTENSITY_BONUS else 0.0
    }

    /**
     * Aggregates games + extra session loads (e.g. the v2 test itself) into
     * total daily cTRIMP keyed by LOCAL date. cTRIMP = Σ minutes × intensity.
     */
    fun dailyCognitiveLoads(
        games: List<GameLoad>,
        extraSessionLoads: Map<LocalDate, Double> = emptyMap(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Map<LocalDate, Double> {
        val daily = HashMap<LocalDate, Double>()
        for (g in games) {
            if (g.minutes <= 0.0) continue
            val day = java.time.Instant.ofEpochMilli(g.endTimeMs).atZone(zone).toLocalDate()
            daily[day] = (daily[day] ?: 0.0) + g.minutes * gameIntensity(g.type, g.rated)
        }
        for ((day, load) in extraSessionLoads) {
            daily[day] = (daily[day] ?: 0.0) + load
        }
        return daily
    }

    /** The workload module's verdict. */
    data class AcwrEvaluation(
        /** EWMA acute/chronic ratio (null = insufficient history → NO_DATA). */
        val ratio: Double?,
        val acuteEwma: Double?,
        val chronicEwma: Double?,
        /** Distinct days with recorded load in the input. */
        val historyDays: Int,
        val tier: V2Tier
    )

    /**
     * EWMA ACWR: iterates day-by-day from the earliest load record through
     * [today] (missing days contribute Load = 0 — rest days still decay the
     * averages), seeding both EMAs with the first day's load.
     *
     * Fewer than [MIN_ACWR_HISTORY_DAYS] distinct loaded days → NO_DATA
     * (the module does not gate during the onboarding fortnight).
     */
    fun evaluateAcwr(dailyLoads: Map<LocalDate, Double>, today: LocalDate): AcwrEvaluation {
        val loadedDays = dailyLoads.count { it.value > 0.0 }
        if (dailyLoads.isEmpty() || loadedDays < MIN_ACWR_HISTORY_DAYS) {
            return AcwrEvaluation(null, null, null, loadedDays, V2Tier.NO_DATA)
        }
        val first = dailyLoads.keys.min()
        val start = if (first < today.minusDays(60)) today.minusDays(60) else first
        var acute = 0.0
        var chronic = 0.0
        var d = start
        var firstDay = true
        while (!d.isAfter(today)) {
            val load = dailyLoads[d] ?: 0.0
            if (firstDay) {
                acute = load
                chronic = load
                firstDay = false
            } else {
                acute = load * LAMBDA_ACUTE + acute * (1 - LAMBDA_ACUTE)
                chronic = load * LAMBDA_CHRONIC + chronic * (1 - LAMBDA_CHRONIC)
            }
            d = d.plusDays(1)
        }
        if (chronic <= 0.0) {
            return AcwrEvaluation(null, acute, chronic, loadedDays, V2Tier.NO_DATA)
        }
        val ratio = acute / chronic
        return AcwrEvaluation(ratio, acute, chronic, loadedDays, workloadTier(ratio))
    }

    /**
     * Workload tier per the matrix. Only the HIGH side gates: a low ACWR
     * ("detraining", < 0.8) is informational — gating on it would create a
     * self-reinforcing lockout (restriction → less play → lower acute load
     * → "detraining" → more restriction). A too-fast return to volume is
     * instead caught by the high-side bands (overreaching / danger spike).
     */
    fun workloadTier(acwr: Double): V2Tier = when {
        acwr > ACWR_DANGER -> V2Tier.TIER3_LOCKOUT
        acwr > ACWR_SWEET_HIGH -> V2Tier.TIER2_RESTRICTED
        else -> V2Tier.TIER1_PEAK
    }

    // ── Master gating ──────────────────────────────────────────────────────

    /** Full input to the gating matrix (each module pre-evaluated). */
    data class V2GatingInput(
        val autonomic: AutonomicEvaluation?,
        val pvt: PvtSummary?,
        val acwr: AcwrEvaluation?,
        /** Past CLEAN PVT runs for the personal Tier-1 speed gate (current run excluded). */
        val speedBaseline: List<SpeedSample> = emptyList(),
        /** When the judged PVT completed (epoch ms; enables circadian matching). */
        val pvtCompletedAtMs: Long? = null
    )

    /** Why each module landed on its tier (human-readable, shown on the result). */
    data class ModuleVerdict(
        val tier: V2Tier,
        val reasons: List<String>
    )

    /** The final gating decision. */
    data class V2GatingResult(
        val tier: V2Tier,
        /** v1-compatible state name (GREEN/YELLOW/RED light). */
        val stateName: String,
        /** v1-compatible composite (drives the shared rest-period ladder). */
        val ccrs: Int,
        val autonomic: ModuleVerdict?,
        val pvt: ModuleVerdict?,
        val workload: ModuleVerdict?,
        /** True when the autonomic tier already forced Tier 2/3 (PVT skipped). */
        val pvtSkipped: Boolean
    )

    /** Maps a v2 tier to the v1 traffic-light state name. */
    fun stateNameFor(tier: V2Tier): String = when (tier) {
        V2Tier.TIER1_PEAK -> ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name
        V2Tier.TIER2_RESTRICTED -> ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name
        V2Tier.TIER3_LOCKOUT -> ChessReadinessEngine.ReadinessState.RED_LIGHT.name
        V2Tier.NO_DATA -> ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name
    }

    /**
     * Synthetic CCRS for the SHARED history record. Enforcement reads the
     * state name; the score only feeds the shared rest-period ladder for
     * failed tests (ccrs < 40 → 120 min, 40–59 → 60 min), so Tier 3 maps
     * to 30 (multiple triggers) or 50 (single trigger), Tier 2 → 65,
     * Tier 1 → 85.
     */
    fun syntheticCcrs(tier: V2Tier, tier3TriggerCount: Int): Int = when (tier) {
        V2Tier.TIER1_PEAK -> 85
        V2Tier.TIER2_RESTRICTED -> 65
        V2Tier.TIER3_LOCKOUT -> if (tier3TriggerCount >= 2) 30 else 50
        V2Tier.NO_DATA -> 65
    }

    /**
     * The gating matrix. Autonomic + workload are evaluated first; if either
     * already forces Tier 2/3 the PVT is SKIPPED (the paper's "protect from
     * unnecessary cognitive strain" rule) — pass pvt = null and pvtSkipped
     * is derived. Otherwise all three modules combine worst-of.
     */
    fun gate(input: V2GatingInput): V2GatingResult {
        val autoVerdict = input.autonomic?.let { verdictAutonomic(it) }
        val loadVerdict = input.acwr?.let { verdictWorkload(it) }
        val speedEval = input.pvt?.let { p ->
            evaluateSpeedBaseline(input.speedBaseline, p.meanRrt, input.pvtCompletedAtMs)
        }
        val pvtVerdict = input.pvt?.let { verdictPvt(it, speedEval) }

        val prePvtTier = (autoVerdict?.tier ?: V2Tier.NO_DATA)
            .worstOf(loadVerdict?.tier ?: V2Tier.NO_DATA)
        val pvtSkipped = input.pvt == null &&
            (autoVerdict?.tier == V2Tier.TIER2_RESTRICTED ||
                autoVerdict?.tier == V2Tier.TIER3_LOCKOUT ||
                loadVerdict?.tier == V2Tier.TIER2_RESTRICTED ||
                loadVerdict?.tier == V2Tier.TIER3_LOCKOUT)

        val tier = if (pvtSkipped) {
            prePvtTier
        } else {
            prePvtTier.worstOf(pvtVerdict?.tier ?: V2Tier.NO_DATA)
        }

        val triggers = listOfNotNull(
            autoVerdict?.tier, pvtVerdict?.tier, loadVerdict?.tier
        ).count { it == V2Tier.TIER3_LOCKOUT }

        return V2GatingResult(
            tier = tier,
            stateName = stateNameFor(tier),
            ccrs = syntheticCcrs(tier, triggers),
            autonomic = autoVerdict,
            pvt = pvtVerdict,
            workload = loadVerdict,
            pvtSkipped = pvtSkipped
        )
    }

    // ── Per-module verdicts with reasons ───────────────────────────────────

    private fun fmt(z: Double): String = String.format("%+.2f", z)

    private fun verdictAutonomic(a: AutonomicEvaluation): ModuleVerdict {
        val reasons = ArrayList<String>()
        if (a.zLnRmssd == null && a.zRhr == null) {
            reasons.add("No biometric baseline yet (need $MIN_BASELINE_SAMPLES days)")
        } else {
            a.zLnRmssd?.let { z ->
                reasons.add(
                    when {
                        z <= -1.5 -> "lnRMSSD ${fmt(z)} — severe sympathetic suppression"
                        z < -0.5 -> "lnRMSSD ${fmt(z)} — moderate HRV suppression"
                        z >= 1.5 -> "lnRMSSD ${fmt(z)} — large spike (caution: Tier 2)"
                        z > 0.5 -> "lnRMSSD ${fmt(z)} — above SWC band (informational)"
                        else -> "lnRMSSD ${fmt(z)} — inside SWC band"
                    }
                )
            }
            a.zRhr?.let { z ->
                reasons.add(
                    when {
                        z >= 1.5 -> "RHR ${fmt(z)} — acute systemic stress"
                        z > 0.5 -> "RHR ${fmt(z)} — moderate elevation"
                        else -> "RHR ${fmt(z)} — stable"
                    }
                )
            }
        }
        return ModuleVerdict(a.tier, reasons)
    }

    private fun verdictPvt(p: PvtSummary, speed: SpeedBaselineEvaluation?): ModuleVerdict {
        val reasons = ArrayList<String>()
        reasons.add(
            when {
                p.lapses >= 5 -> "${p.lapses} lapses — profound vigilance impairment"
                p.lapses >= 2 -> "${p.lapses} lapses — moderate cognitive slowing"
                else -> "${p.lapses} lapse(s)"
            }
        )
        reasons.add(
            when {
                p.falseStarts >= 4 -> "${p.falseStarts} false starts — severe impulsivity"
                p.falseStarts >= 2 -> "${p.falseStarts} false starts — elevated impulsivity"
                else -> "${p.falseStarts} false start(s)"
            }
        )
        p.meanRrt?.let { reasons.add(String.format("Response speed %.2f (1000/RT)", it)) }
        speed?.let { s ->
            reasons.add(
                when {
                    s.demotesTier1 -> String.format(
                        "P%.0f vs your %d clean runs — personal bottom %d%% (Tier 2)",
                        s.percentile ?: 0.0, s.baselineN, SPEED_DEMOTE_PERCENTILE.toInt()
                    )
                    s.active -> String.format(
                        "P%.0f vs your %d clean runs%s",
                        s.percentile ?: 0.0, s.baselineN,
                        if (s.hourMatched) " (same-hour)" else ""
                    )
                    else -> "Personal speed baseline building (${s.baselineN}/$MIN_SPEED_BASELINE_SAMPLES clean runs)"
                }
            )
        }
        val tier = if (speed?.demotesTier1 == true && p.tier == V2Tier.TIER1_PEAK) {
            V2Tier.TIER2_RESTRICTED
        } else p.tier
        return ModuleVerdict(tier, reasons)
    }

    private fun verdictWorkload(w: AcwrEvaluation): ModuleVerdict {
        val reasons = ArrayList<String>()
        val r = w.ratio
        reasons.add(
            when {
                r == null -> "Only ${w.historyDays} load day(s) — baseline building"
                r > ACWR_DANGER -> String.format("ACWR %.2f — danger zone spike", r)
                r > ACWR_SWEET_HIGH -> String.format("ACWR %.2f — overreaching", r)
                r < ACWR_SWEET_LOW -> String.format("ACWR %.2f — detraining (informational, does not gate)", r)
                else -> String.format("ACWR %.2f — sweet spot", r)
            }
        )
        return ModuleVerdict(w.tier, reasons)
    }
}

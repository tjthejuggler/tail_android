package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import com.example.tail.widget.ChessReadinessV2Engine
import com.example.tail.widget.ChessReadinessV2Engine.AcwrEvaluation
import com.example.tail.widget.ChessReadinessV2Engine.AutonomicEvaluation
import com.example.tail.widget.ChessReadinessV2Engine.DailyBiometric
import com.example.tail.widget.ChessReadinessV2Engine.GameLoad
import com.example.tail.widget.ChessReadinessV2Engine.PvtClassification
import com.example.tail.widget.ChessReadinessV2Engine.PvtSample
import com.example.tail.widget.ChessReadinessV2Engine.PvtSummary
import com.example.tail.widget.ChessReadinessV2Engine.V2GatingInput
import com.example.tail.widget.ChessReadinessV2Engine.V2Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for the v2 Cognitive Readiness Gating engine — the research
 * paper's math verified end to end: lnRMSSD/RHR Z-scores, PVT-B thresholds,
 * EWMA cognitive ACWR and the 3-tier gating matrix.
 */
class ChessReadinessV2EngineTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 24)

    // ── EWMA + baseline statistics ─────────────────────────────────────────

    @Test
    fun ewma_seedsWithFirstValueAndDecays() {
        assertNull(ChessReadinessV2Engine.ewma(emptyList(), 0.25))
        assertEquals(10.0, ChessReadinessV2Engine.ewma(listOf(10.0), 0.25)!!, 1e-9)
        // e0=0, three more zeros keep 0, then 100·0.5 = 50 with λ=0.5.
        assertEquals(50.0, ChessReadinessV2Engine.ewma(listOf(0.0, 0.0, 0.0, 0.0, 100.0), 0.5)!!, 1e-9)
        // A constant series stays constant.
        assertEquals(7.0, ChessReadinessV2Engine.ewma(List(20) { 7.0 }, 0.25)!!, 1e-9)
    }

    @Test
    fun baselineStats_computesSampleSd() {
        assertNull(ChessReadinessV2Engine.baselineStats(listOf(5.0)))
        val s = ChessReadinessV2Engine.baselineStats(listOf(2.0, 4.0))!!
        assertEquals(3.0, s.mean, 1e-9)
        assertEquals(Math.sqrt(2.0), s.sd, 1e-9)
        assertEquals(2, s.n)
    }

    @Test
    fun lnRmssd_isNaturalLog() {
        assertEquals(Math.log(100.0), ChessReadinessV2Engine.lnRmssd(100), 1e-9)
    }

    // ── Autonomic module ───────────────────────────────────────────────────

    @Test
    fun autonomic_insufficientBaseline_isNoDataAndDoesNotGate() {
        // Only 6 baseline days → below MIN_BASELINE_SAMPLES (7).
        val history = (1..6).map {
            DailyBiometric(today.minusDays(it.toLong()), 100, 50)
        }
        val e = ChessReadinessV2Engine.evaluateAutonomic(history, today)
        assertNull(e.zLnRmssd)
        assertNull(e.zRhr)
        // A missing module never gates: tier stays PEAK.
        assertEquals(V2Tier.TIER1_PEAK, e.tier)
    }

    @Test
    fun autonomic_stableHistory_isTier1() {
        val history = (0..30).map {
            DailyBiometric(today.minusDays(it.toLong()), 100, 50)
        }
        val e = ChessReadinessV2Engine.evaluateAutonomic(history, today)
        assertEquals(V2Tier.TIER1_PEAK, e.tier)
        assertEquals(0.0, e.zLnRmssd!!, 0.05)
        assertEquals(0.0, e.zRhr!!, 0.05)
    }

    @Test
    fun autonomic_suppressedHrv_isTier3() {
        // 24 baseline days at RMSSD 100, then an acute week at 40.
        val history = (0..30).map {
            val d = today.minusDays(it.toLong())
            val rmssd = if (it <= 6) 40 else 100
            DailyBiometric(d, rmssd, 50)
        }
        val e = ChessReadinessV2Engine.evaluateAutonomic(history, today)
        assertTrue("z=${e.zLnRmssd}", e.zLnRmssd!! <= -1.5)
        assertEquals(V2Tier.TIER3_LOCKOUT, e.tier)
    }

    @Test
    fun autonomic_largeHrvSpike_isTier2Only_neverLockout() {
        // A large HRV spike ABOVE baseline cautions (Tier 2) but never locks
        // out — rising HRV is usually recovery, and the "paradoxical surge"
        // evidence is too weak to justify a lockout.
        val history = (0..30).map {
            val d = today.minusDays(it.toLong())
            val rmssd = if (it <= 6) 200 else 100
            DailyBiometric(d, rmssd, 50)
        }
        val e = ChessReadinessV2Engine.evaluateAutonomic(history, today)
        assertTrue("z=${e.zLnRmssd}", e.zLnRmssd!! >= 1.5)
        assertEquals(V2Tier.TIER2_RESTRICTED, e.tier)
    }

    @Test
    fun autonomic_moderateHrvElevation_isInformational() {
        // +0.5…+1.5 elevation (e.g. HRV returning to a pre-injury level the
        // rolling μ30 no longer represents) does NOT gate at all.
        val t = ChessReadinessV2Engine::autonomicTier
        assertEquals(V2Tier.TIER1_PEAK, t(0.6, null))
        assertEquals(V2Tier.TIER1_PEAK, t(1.0, null))
        assertEquals(V2Tier.TIER1_PEAK, t(1.49, null))
    }

    @Test
    fun autonomic_elevatedRhr_isTier3_decreasedRhr_isFine() {
        val elevated = (0..30).map {
            val d = today.minusDays(it.toLong())
            val rhr = if (it <= 6) 58 else 50
            DailyBiometric(d, 100, rhr)
        }
        val eUp = ChessReadinessV2Engine.evaluateAutonomic(elevated, today)
        assertTrue("z=${eUp.zRhr}", eUp.zRhr!! >= 1.5)
        assertEquals(V2Tier.TIER3_LOCKOUT, eUp.tier)

        // RHR is one-sided: a DECREASE is never penalized.
        val decreased = (0..30).map {
            val d = today.minusDays(it.toLong())
            val rhr = if (it <= 6) 42 else 50
            DailyBiometric(d, 100, rhr)
        }
        val eDown = ChessReadinessV2Engine.evaluateAutonomic(decreased, today)
        assertTrue(eDown.zRhr!! < 0.0)
        assertEquals(V2Tier.TIER1_PEAK, eDown.tier)
    }

    @Test
    fun autonomic_sdFloor_preventsDivisionBlowupOnConstantBaseline() {
        // Constant 50 bpm baseline (σ = 0 → floor 0.5), today reads 51.
        val history = (1..30).map {
            DailyBiometric(today.minusDays(it.toLong()), 100, 50)
        } + DailyBiometric(today, 100, 51)
        val e = ChessReadinessV2Engine.evaluateAutonomic(history, today)
        // Acute EWMA = 0.25·51 + 0.75·50 = 50.25 → z = 0.25/0.5 = 0.5 → Tier 1.
        assertEquals(0.5, e.zRhr!!, 1e-9)
        assertEquals(V2Tier.TIER1_PEAK, e.tier)
    }

    @Test
    fun autonomicTier_exactBandBoundaries() {
        val t = ChessReadinessV2Engine::autonomicTier
        // lnRMSSD asymmetric bands: suppression gates, elevation is
        // informational up to +1.5 and caps at Tier 2.
        assertEquals(V2Tier.TIER1_PEAK, t(0.0, null))
        assertEquals(V2Tier.TIER1_PEAK, t(-0.5, null))
        assertEquals(V2Tier.TIER1_PEAK, t(0.5, null))
        assertEquals(V2Tier.TIER1_PEAK, t(0.51, null))
        assertEquals(V2Tier.TIER1_PEAK, t(1.49, null))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(-0.51, null))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(1.5, null))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(2.0, null))
        assertEquals(V2Tier.TIER3_LOCKOUT, t(-1.5, null))
        // RHR one-sided bands.
        assertEquals(V2Tier.TIER1_PEAK, t(null, 0.5))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(null, 0.51))
        assertEquals(V2Tier.TIER3_LOCKOUT, t(null, 1.5))
        assertEquals(V2Tier.TIER1_PEAK, t(null, -3.0))
        // Worst-of across the two metrics (elevation 0.6 no longer gates,
        // so the Tier-2 worst-of case uses suppression).
        assertEquals(V2Tier.TIER3_LOCKOUT, t(0.0, 2.0))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(-0.6, -1.0))
        assertEquals(V2Tier.TIER1_PEAK, t(0.6, -1.0))
    }

    // ── PVT-B module ───────────────────────────────────────────────────────

    @Test
    fun pvt_classificationThresholds() {
        val c = ChessReadinessV2Engine::classifyPvtResponse
        assertEquals(PvtClassification.FalseStart, c(null))
        assertEquals(PvtClassification.FalseStart, c(-50))   // before stimulus
        assertEquals(PvtClassification.FalseStart, c(99))    // < 100 ms
        assertEquals(PvtClassification.Valid, c(100))        // exactly 100 ms is possible
        assertEquals(PvtClassification.Valid, c(250))
        assertEquals(PvtClassification.Valid, c(354))
        assertEquals(PvtClassification.Lapse, c(355))        // recalibrated 3-min threshold
        assertEquals(PvtClassification.Lapse, c(900))
    }

    @Test
    fun pvt_summaryAggregatesReciprocalRt() {
        val s = ChessReadinessV2Engine.summarizePvt(
            listOf(PvtSample(250), PvtSample(300), PvtSample(400), PvtSample(null), PvtSample(90))
        )
        assertEquals(3, s.validResponses)   // lapses count as valid responses
        assertEquals(1, s.lapses)
        assertEquals(2, s.falseStarts)
        assertEquals(316.6667, s.meanRtMs!!, 0.001)
        assertEquals((1000.0 / 250 + 1000.0 / 300 + 1000.0 / 400) / 3, s.meanRrt!!, 1e-9)
        assertEquals(400, s.maxRtMs)
        // 1 lapse + 2 false starts → false starts ≥ 2 → Tier 2.
        assertEquals(V2Tier.TIER2_RESTRICTED, s.tier)
    }

    @Test
    fun pvtTier_exactBoundaries() {
        val t = ChessReadinessV2Engine::pvtTier
        assertEquals(V2Tier.TIER1_PEAK, t(0, 0))
        assertEquals(V2Tier.TIER1_PEAK, t(1, 1))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(2, 0))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(0, 2))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(4, 3))
        assertEquals(V2Tier.TIER3_LOCKOUT, t(5, 0))
        assertEquals(V2Tier.TIER3_LOCKOUT, t(0, 4))
    }

    // ── Cognitive load / ACWR module ───────────────────────────────────────

    @Test
    fun gameIntensity_byClassAndRatedBonus() {
        val g = ChessReadinessV2Engine::gameIntensity
        assertEquals(9.0, g("RAPID", true), 1e-9)
        assertEquals(8.0, g("RAPID", false), 1e-9)
        assertEquals(6.0, g("blitz", false), 1e-9)   // case-insensitive
        assertEquals(7.0, g("BLITZ", true), 1e-9)
        assertEquals(5.0, g("BULLET", false), 1e-9)
        assertEquals(6.0, g("DAILY", false), 1e-9)   // unknown → blitz base
    }

    @Test
    fun dailyCognitiveLoads_aggregatesGamesAndExtraSessions() {
        val zone = java.time.ZoneId.of("UTC")
        val day = LocalDate.of(2026, 8, 20)
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val loads = ChessReadinessV2Engine.dailyCognitiveLoads(
            games = listOf(
                GameLoad(t0 + 60_000, 10.0, "BLITZ", rated = true),   // 10×7 = 70
                GameLoad(t0 + 120_000, 10.0, "BLITZ", rated = true),  // +70
                GameLoad(t0 + 180_000, 0.0, "BULLET", rated = false), // skipped
                GameLoad(t0 + 86_400_000, 10.0, "BULLET", rated = false) // next day: 50
            ),
            extraSessionLoads = mapOf(day to 12.0),
            zone = zone
        )
        assertEquals(152.0, loads[day]!!, 1e-9)
        assertEquals(50.0, loads[day.plusDays(1)]!!, 1e-9)
    }

    @Test
    fun acwr_insufficientHistory_isNoData() {
        val loads = (1..10).associate {
            today.minusDays(it.toLong()) to 60.0
        }
        val e = ChessReadinessV2Engine.evaluateAcwr(loads, today)
        assertEquals(V2Tier.NO_DATA, e.tier)
        assertNull(e.ratio)
        assertEquals(10, e.historyDays)
    }

    @Test
    fun acwr_steadyLoad_isSweetSpot() {
        // 30 loaded days INCLUDING today (the engine iterates through today —
        // games played before the test count towards the acute load).
        val loads = (0..29).associate {
            today.minusDays(it.toLong()) to 60.0
        }
        val e = ChessReadinessV2Engine.evaluateAcwr(loads, today)
        assertEquals(V2Tier.TIER1_PEAK, e.tier)
        assertEquals(1.0, e.ratio!!, 0.01)
    }

    @Test
    fun acwr_spike_isTier3() {
        // 23 days at 60, then an acute week (incl. today) at 240 → ratio ≈ 1.63.
        val loads = (0..29).associate {
            val d = today.minusDays(it.toLong())
            d to if (it <= 6) 240.0 else 60.0
        }
        val e = ChessReadinessV2Engine.evaluateAcwr(loads, today)
        assertEquals(V2Tier.TIER3_LOCKOUT, e.tier)
        assertTrue("ratio=${e.ratio}", e.ratio!! > 1.5)
    }

    @Test
    fun acwr_moderateSpike_isTier2() {
        // 23 days at 60, then a week (incl. today) at 130 → ratio ≈ 1.37 (overreaching band).
        val loads = (0..29).associate {
            val d = today.minusDays(it.toLong())
            d to if (it <= 6) 130.0 else 60.0
        }
        val e = ChessReadinessV2Engine.evaluateAcwr(loads, today)
        assertEquals(V2Tier.TIER2_RESTRICTED, e.tier)
        assertTrue("ratio=${e.ratio}", e.ratio!! > 1.3 && e.ratio!! <= 1.5)
    }

    @Test
    fun acwr_restWeek_isInformationalOnly() {
        // 30 loaded days, then 7 zero days → acute collapses, ratio ≈ 0.2.
        // The LOW side must NOT gate: restricting play because play has been
        // scarce is a self-reinforcing lockout.
        val loads = (8..37).associate {
            today.minusDays(it.toLong()) to 60.0
        }
        val e = ChessReadinessV2Engine.evaluateAcwr(loads, today)
        assertEquals(V2Tier.TIER1_PEAK, e.tier)
        assertTrue("ratio=${e.ratio}", e.ratio!! < 0.8)
    }

    @Test
    fun workloadTier_exactBands_highSideOnly() {
        val t = ChessReadinessV2Engine::workloadTier
        assertEquals(V2Tier.TIER1_PEAK, t(0.0))
        assertEquals(V2Tier.TIER1_PEAK, t(0.79))
        assertEquals(V2Tier.TIER1_PEAK, t(0.8))
        assertEquals(V2Tier.TIER1_PEAK, t(1.0))
        assertEquals(V2Tier.TIER1_PEAK, t(1.3))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(1.31))
        assertEquals(V2Tier.TIER2_RESTRICTED, t(1.5))
        assertEquals(V2Tier.TIER3_LOCKOUT, t(1.51))
    }

    // ── Gating matrix ──────────────────────────────────────────────────────

    private fun autoEval(zLn: Double?, zRhr: Double?): AutonomicEvaluation =
        AutonomicEvaluation(
            zLnRmssd = zLn, zRhr = zRhr,
            baselineLnRmssdMean = null, baselineRhrMean = null,
            acuteLnRmssd = null, acuteRhr = null,
            lnRmssdSamples = 30, rhrSamples = 30,
            tier = ChessReadinessV2Engine.autonomicTier(zLn, zRhr)
        )

    private fun pvtEval(lapses: Int, falseStarts: Int): PvtSummary =
        PvtSummary(
            validResponses = lapses + falseStarts + 10,
            lapses = lapses, falseStarts = falseStarts,
            meanRrt = 3.5, meanRtMs = 285.0, maxRtMs = 500,
            tier = ChessReadinessV2Engine.pvtTier(lapses, falseStarts)
        )

    private fun acwrEval(ratio: Double?): AcwrEvaluation =
        AcwrEvaluation(
            ratio = ratio, acuteEwma = null, chronicEwma = null,
            historyDays = 30,
            tier = ratio?.let { ChessReadinessV2Engine.workloadTier(it) } ?: V2Tier.NO_DATA
        )

    @Test
    fun worstOf_followsLogicalOrDownTiers() {
        assertEquals(V2Tier.TIER3_LOCKOUT, V2Tier.TIER3_LOCKOUT.worstOf(V2Tier.TIER1_PEAK))
        assertEquals(V2Tier.TIER3_LOCKOUT, V2Tier.TIER1_PEAK.worstOf(V2Tier.TIER3_LOCKOUT))
        assertEquals(V2Tier.TIER2_RESTRICTED, V2Tier.TIER1_PEAK.worstOf(V2Tier.TIER2_RESTRICTED))
        assertEquals(V2Tier.TIER1_PEAK, V2Tier.TIER1_PEAK.worstOf(V2Tier.TIER1_PEAK))
        // NO_DATA never gates — the other side wins.
        assertEquals(V2Tier.TIER3_LOCKOUT, V2Tier.NO_DATA.worstOf(V2Tier.TIER3_LOCKOUT))
        assertEquals(V2Tier.TIER2_RESTRICTED, V2Tier.TIER2_RESTRICTED.worstOf(V2Tier.NO_DATA))
        assertEquals(V2Tier.NO_DATA, V2Tier.NO_DATA.worstOf(V2Tier.NO_DATA))
    }

    @Test
    fun gate_allPeak_isGreenWithCcrs85() {
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(0.0, 0.0), pvtEval(0, 0), acwrEval(1.0))
        )
        assertEquals(V2Tier.TIER1_PEAK, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name, r.stateName)
        assertEquals(85, r.ccrs)
        assertFalse(r.pvtSkipped)
    }

    @Test
    fun gate_worstModuleWins_pvtTier3Alone() {
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(0.0, 0.0), pvtEval(5, 0), acwrEval(1.0))
        )
        assertEquals(V2Tier.TIER3_LOCKOUT, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.RED_LIGHT.name, r.stateName)
        assertEquals(50, r.ccrs)   // single Tier-3 trigger
        assertFalse(r.pvtSkipped)
    }

    @Test
    fun gate_multipleTier3Triggers_mapToHarsherCcrs() {
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(-2.0, null), pvtEval(5, 4), acwrEval(1.0))
        )
        assertEquals(V2Tier.TIER3_LOCKOUT, r.tier)
        assertEquals(30, r.ccrs)   // autonomic + PVT both Tier 3
    }

    @Test
    fun gate_autonomicTier2_skipsPvt() {
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(-0.8, null), null, acwrEval(1.0))
        )
        assertTrue(r.pvtSkipped)
        assertEquals(V2Tier.TIER2_RESTRICTED, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name, r.stateName)
        assertEquals(65, r.ccrs)
        assertNull(r.pvt)
    }

    @Test
    fun gate_workloadTier3_skipsPvt() {
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(0.0, 0.0), null, acwrEval(1.8))
        )
        assertTrue(r.pvtSkipped)
        assertEquals(V2Tier.TIER3_LOCKOUT, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.RED_LIGHT.name, r.stateName)
    }

    @Test
    fun gate_allModulesMissing_isConservativeYellow() {
        val r = ChessReadinessV2Engine.gate(V2GatingInput(null, null, null))
        assertEquals(V2Tier.NO_DATA, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name, r.stateName)
        assertEquals(65, r.ccrs)
        assertFalse(r.pvtSkipped)
    }

    @Test
    fun gate_noDataModules_doNotGateAPeakPvt() {
        // Autonomic + workload NO_DATA, clean PVT → the PVT decides alone.
        val r = ChessReadinessV2Engine.gate(
            V2GatingInput(autoEval(null, null), pvtEval(0, 0), acwrEval(null))
        )
        assertEquals(V2Tier.TIER1_PEAK, r.tier)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name, r.stateName)
    }

    @Test
    fun stateNames_mapToV1TrafficLights() {
        assertEquals(
            ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name,
            ChessReadinessV2Engine.stateNameFor(V2Tier.TIER1_PEAK)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name,
            ChessReadinessV2Engine.stateNameFor(V2Tier.TIER2_RESTRICTED)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.RED_LIGHT.name,
            ChessReadinessV2Engine.stateNameFor(V2Tier.TIER3_LOCKOUT)
        )
    }

    @Test
    fun syntheticCcrs_ladder() {
        assertEquals(85, ChessReadinessV2Engine.syntheticCcrs(V2Tier.TIER1_PEAK, 0))
        assertEquals(65, ChessReadinessV2Engine.syntheticCcrs(V2Tier.TIER2_RESTRICTED, 0))
        assertEquals(50, ChessReadinessV2Engine.syntheticCcrs(V2Tier.TIER3_LOCKOUT, 1))
        assertEquals(30, ChessReadinessV2Engine.syntheticCcrs(V2Tier.TIER3_LOCKOUT, 2))
    }
}

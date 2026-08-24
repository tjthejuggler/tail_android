package com.example.tail

import com.example.tail.data.ChessComGameDetail
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2V2Engine
import com.example.tail.widget.ChessPhase2V2Engine.AcwrInput
import com.example.tail.widget.ChessPhase2V2Engine.Baseline
import com.example.tail.widget.ChessPhase2V2Engine.GameInputV2
import com.example.tail.widget.ChessPhase2V2Engine.MappingV2
import com.example.tail.widget.ChessPhase2V2Engine.SessionGameV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 2 v2 post-game audit engine
 * ([ChessPhase2V2Engine]) — research-report rules 1–5, personal Z-score
 * baselines, the circadian adjustment, PGN clock math and the game → input
 * mapping. The engine is pure, so every test builds inputs directly.
 */
class ChessPhase2V2EngineTest {

    private val E = ChessPhase2V2Engine
    private val NOW = 1_700_000_000_000L
    private val MIN = 60_000L

    private val WIN = ChessPhase2Engine.GameResult.WIN
    private val DRAW = ChessPhase2Engine.GameResult.DRAW
    private val LOSS = ChessPhase2Engine.GameResult.LOSS
    private val CONTINUE = ChessPhase2Engine.OutputState.CONTINUE_RATED
    private val PIVOT = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS
    private val TERMINATE = ChessPhase2Engine.OutputState.TERMINATE_SESSION

    // ── Builders ────────────────────────────────────────────────────────────

    private fun input(
        result: ChessPhase2Engine.GameResult = WIN,
        accuracy: Double? = null,
        avgMoveSec: Double? = null,
        sessionMins: Int = 10,
        localHour: Int = 14,
        shortGame: Boolean = false
    ): GameInputV2 = GameInputV2(
        timeControl = ChessPhase2Engine.TimeControl.BLITZ,
        result = result,
        accuracy = accuracy,
        avgMoveSec = avgMoveSec,
        sessionElapsedMins = sessionMins,
        localHour = localHour,
        shortGame = shortGame
    )

    private fun gameAt(
        minutesAgo: Long,
        result: ChessPhase2Engine.GameResult,
        state: ChessPhase2Engine.OutputState
    ): SessionGameV2 = SessionGameV2(NOW - minutesAgo * MIN, result, state.name)

    private fun yellowAt(minutesAgo: Long): SessionGameV2 =
        gameAt(minutesAgo, WIN, PIVOT)

    /** [count] consecutive session losses, most recent last. */
    private fun lossesBefore(count: Int): List<SessionGameV2> =
        (1..count).map { i -> gameAt(((count - i + 1) * 10).toLong(), LOSS, CONTINUE) }

    private fun accBaseline(mean: Double = 80.0, sd: Double = 5.0, n: Int = 30): Baseline =
        Baseline(mean, sd, n)

    private fun moveBaseline(mean: Double = 20.0, sd: Double = 4.0, n: Int = 30): Baseline =
        Baseline(mean, sd, n)

    private fun acwrInput(
        acute: Int,
        chronicWeekly: Double,
        days: Int = 20
    ): AcwrInput = AcwrInput(acute, chronicWeekly, days)

    private fun eval(
        input: GameInputV2,
        session: List<SessionGameV2> = emptyList(),
        acc: Baseline? = null,
        move: Baseline? = null,
        acwr: AcwrInput? = null,
        now: Long = NOW
    ) = E.evaluate(input, session, acc, move, acwr, now)

    // ── Green path ──────────────────────────────────────────────────────────

    @Test
    fun clean_game_is_green() {
        val r = eval(input())
        assertEquals(CONTINUE, r.outputState)
        assertTrue(r.redRules.isEmpty())
        assertTrue(r.yellowRules.isEmpty())
        assertFalse(r.hysteresisHeld)
        assertEquals("NO_RISK_SIGNALS", r.reason)
        assertTrue(r.message.contains("Cleared"))
    }

    // ── Rule 1: hard fatigue limit ──────────────────────────────────────────

    @Test
    fun rule1_green_at_90_minutes() {
        assertEquals(CONTINUE, eval(input(sessionMins = 90)).outputState)
    }

    @Test
    fun rule1_yellow_above_90_minutes() {
        val r = eval(input(sessionMins = 91))
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_1_TIME_ON_TASK"))
        assertTrue(r.redRules.isEmpty())
    }

    @Test
    fun rule1_yellow_at_exactly_120_minutes() {
        assertEquals(PIVOT, eval(input(sessionMins = 120)).outputState)
    }

    @Test
    fun rule1_red_above_120_minutes() {
        val r = eval(input(sessionMins = 121))
        assertEquals(TERMINATE, r.outputState)
        assertTrue(r.redRules.contains("RULE_1_TIME_ON_TASK"))
    }

    // ── Rule 2: loss-chasing / streak dynamics ─────────────────────────────

    @Test
    fun rule2_single_loss_never_flags_bounce_back() {
        val r = eval(input(result = LOSS))
        assertEquals(CONTINUE, r.outputState)
        assertEquals(1, r.consecutiveLosses)
        assertFalse(r.yellowRules.contains("RULE_2_LOSS_STREAK"))
        assertFalse(r.redRules.contains("RULE_2_LOSS_STREAK"))
    }

    @Test
    fun rule2_two_consecutive_losses_yellow() {
        val r = eval(input(result = LOSS), session = lossesBefore(1))
        assertEquals(PIVOT, r.outputState)
        assertEquals(2, r.consecutiveLosses)
        assertTrue(r.yellowRules.contains("RULE_2_LOSS_STREAK"))
    }

    @Test
    fun rule2_three_consecutive_losses_red() {
        val r = eval(input(result = LOSS), session = lossesBefore(2))
        assertEquals(TERMINATE, r.outputState)
        assertEquals(3, r.consecutiveLosses)
        assertTrue(r.redRules.contains("RULE_2_LOSS_STREAK"))
    }

    @Test
    fun rule2_win_resets_streak_counter() {
        val r = eval(input(result = WIN), session = lossesBefore(2))
        assertEquals(CONTINUE, r.outputState)
        assertEquals(0, r.consecutiveLosses)
    }

    @Test
    fun rule2_streak_broken_by_earlier_win_counts_only_tail() {
        // Session (oldest → newest): LOSS, WIN, LOSS — auditing a new LOSS.
        val session = listOf(
            gameAt(30, LOSS, CONTINUE),
            gameAt(20, WIN, CONTINUE),
            gameAt(10, LOSS, CONTINUE)
        )
        val r = eval(input(result = LOSS), session = session)
        assertEquals(2, r.consecutiveLosses)
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_2_LOSS_STREAK"))
    }

    // ── Rule 3: tilt vector (speed × accuracy) ──────────────────────────────

    @Test
    fun rule3_red_tilt_fast_and_poor_on_loss() {
        // zMove = (10 − 20)/4 = −2.5; deficit = 80 − 65 = 15 → z = +3.0.
        val r = eval(
            input(result = LOSS, accuracy = 65.0, avgMoveSec = 10.0),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(TERMINATE, r.outputState)
        assertTrue(r.redRules.contains("RULE_3_TILT_VECTOR"))
        assertEquals(-2.5, r.zMoveTime!!, 0.0)
        assertEquals(3.0, r.zDeficit!!, 0.0)
    }

    @Test
    fun rule3_fast_and_poor_win_is_yellow_not_red() {
        // Same tilt telemetry but a WIN — red requires the loss check.
        val r = eval(
            input(result = WIN, accuracy = 65.0, avgMoveSec = 10.0),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_3_TILT_VECTOR"))
        assertFalse(r.redRules.contains("RULE_3_TILT_VECTOR"))
    }

    @Test
    fun rule3_yellow_tier_on_softer_thresholds() {
        // zMove = (15.2 − 20)/4 = −1.2; deficit = 80 − 74 = 6 → z = +1.2.
        val r = eval(
            input(result = WIN, accuracy = 74.0, avgMoveSec = 15.2),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_3_TILT_VECTOR"))
        assertTrue(r.redRules.isEmpty())
    }

    @Test
    fun rule3_fast_but_accurate_is_green() {
        // zMove = −2.5 but deficit z = (80 − 85)/5 = −1.0 → no tilt.
        val r = eval(
            input(result = LOSS, accuracy = 85.0, avgMoveSec = 10.0),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(CONTINUE, r.outputState)
    }

    @Test
    fun rule3_does_not_gate_without_ready_accuracy_baseline() {
        // Only 10 samples → accuracy Z is null → tilt vector cannot fire.
        val r = eval(
            input(result = LOSS, accuracy = 65.0, avgMoveSec = 10.0),
            acc = accBaseline(n = 10), move = moveBaseline()
        )
        assertEquals(CONTINUE, r.outputState)
        assertNull(r.zDeficit)
    }

    @Test
    fun rule3_short_game_accuracy_is_ignored() {
        val r = eval(
            input(result = LOSS, accuracy = 50.0, avgMoveSec = 10.0, shortGame = true),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(CONTINUE, r.outputState)
        assertNull(r.zDeficit)
    }

    @Test
    fun rule3_null_telemetry_never_gates() {
        val r = eval(input(result = LOSS), acc = accBaseline(), move = moveBaseline())
        assertEquals(CONTINUE, r.outputState)
        assertNull(r.zMoveTime)
        assertNull(r.zDeficit)
    }

    // ── Circadian adjustment ────────────────────────────────────────────────

    @Test
    fun circadian_window_hours() {
        // 20:00–04:00 (start inclusive, end exclusive).
        assertTrue(E.isCircadianWindow(20))
        assertTrue(E.isCircadianWindow(23))
        assertTrue(E.isCircadianWindow(0))
        assertTrue(E.isCircadianWindow(3))
        assertFalse(E.isCircadianWindow(4))
        assertFalse(E.isCircadianWindow(14))
        assertFalse(E.isCircadianWindow(19))
    }

    @Test
    fun circadian_relaxes_speed_baseline_at_night() {
        // Baseline 22 s/move, SD 4. Played 15 s/move with poor accuracy (z=+2).
        //  Day (hour 14): zMove = (15 − 22)/4 = −1.75 → red-tier speed.
        //  Night (hour 23): norm is 22/1.1 ≈ 20 → zMove ≈ −1.25 → yellow-tier.
        val day = eval(
            input(result = LOSS, accuracy = 70.0, avgMoveSec = 15.0, localHour = 14),
            acc = accBaseline(), move = moveBaseline(mean = 22.0)
        )
        assertEquals(TERMINATE, day.outputState)
        assertTrue(day.redRules.contains("RULE_3_TILT_VECTOR"))
        assertFalse(day.circadianAdjusted)
        assertEquals(-1.75, day.zMoveTime!!, 0.001)

        val night = eval(
            input(result = LOSS, accuracy = 70.0, avgMoveSec = 15.0, localHour = 23),
            acc = accBaseline(), move = moveBaseline(mean = 22.0)
        )
        assertEquals(PIVOT, night.outputState)
        assertTrue(night.yellowRules.contains("RULE_3_TILT_VECTOR"))
        assertFalse(night.redRules.contains("RULE_3_TILT_VECTOR"))
        assertTrue(night.circadianAdjusted)
        assertEquals(-1.25, night.zMoveTime!!, 0.001)
        assertEquals(20.0, night.moveBaseline!!.mean, 0.001)
    }

    @Test
    fun circadian_flag_false_without_move_baseline() {
        val r = eval(input(result = LOSS, localHour = 23), acc = accBaseline())
        assertFalse(r.circadianAdjusted)
    }

    // ── Rule 4: chronic overload (ACWR) ─────────────────────────────────────

    @Test
    fun acwr_sweet_spot_is_green() {
        val r = eval(input(), acwr = acwrInput(acute = 10, chronicWeekly = 10.0))
        assertEquals(CONTINUE, r.outputState)
        assertTrue(r.acwrGated)
        assertEquals(1.0, r.acwr!!, 0.0)
    }

    @Test
    fun acwr_yellow_at_1_3() {
        val r = eval(input(), acwr = acwrInput(acute = 13, chronicWeekly = 10.0))
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_4_CHRONIC_OVERLOAD"))
    }

    @Test
    fun acwr_red_at_1_5() {
        val r = eval(input(), acwr = acwrInput(acute = 15, chronicWeekly = 10.0))
        assertEquals(TERMINATE, r.outputState)
        assertTrue(r.redRules.contains("RULE_4_CHRONIC_OVERLOAD"))
    }

    @Test
    fun acwr_infinite_when_chronic_zero() {
        val r = eval(input(), acwr = acwrInput(acute = 5, chronicWeekly = 0.0))
        assertEquals(TERMINATE, r.outputState)
        assertTrue(r.redRules.contains("RULE_4_CHRONIC_OVERLOAD"))
        assertEquals(Double.POSITIVE_INFINITY, r.acwr!!, 0.0)
    }

    @Test
    fun acwr_below_min_history_does_not_gate() {
        // 13 distinct days (< 14): ratio would be 3.0 but must not gate.
        val r = eval(input(), acwr = acwrInput(acute = 30, chronicWeekly = 10.0, days = 13))
        assertEquals(CONTINUE, r.outputState)
        assertFalse(r.acwrGated)
        assertNull(r.acwr)
    }

    @Test
    fun acwr_null_input_does_not_gate() {
        val r = eval(input(), acwr = null)
        assertEquals(CONTINUE, r.outputState)
        assertFalse(r.acwrGated)
    }

    // ── Rule 5: hysteresis ──────────────────────────────────────────────────

    @Test
    fun hysteresis_holds_yellow_under_15_minutes() {
        val r = eval(
            input(result = WIN, accuracy = 85.0, avgMoveSec = 20.0),
            session = listOf(yellowAt(14)),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.hysteresisHeld)
        assertEquals("RULE_5_HYSTERESIS", r.reason)
        assertTrue(r.message.contains("hysteresis"))
    }

    @Test
    fun hysteresis_released_after_win_accuracy_and_time() {
        // Win + accuracy above norm (zDeficit = −1.0) + 20 min since yellow.
        val r = eval(
            input(result = WIN, accuracy = 85.0, avgMoveSec = 20.0),
            session = listOf(yellowAt(20)),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(CONTINUE, r.outputState)
        assertFalse(r.hysteresisHeld)
    }

    @Test
    fun hysteresis_not_released_on_loss() {
        val r = eval(
            input(result = LOSS, accuracy = 85.0, avgMoveSec = 20.0),
            session = listOf(yellowAt(20)),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.hysteresisHeld)
    }

    @Test
    fun hysteresis_not_released_with_positive_deficit_z() {
        // Win, 20 min passed, but accuracy still below norm (zDeficit = +0.5).
        val r = eval(
            input(result = WIN, accuracy = 77.5, avgMoveSec = 20.0),
            session = listOf(yellowAt(20)),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.hysteresisHeld)
        assertEquals(0.5, r.zDeficit!!, 0.0)
    }

    @Test
    fun hysteresis_uses_most_recent_yellow() {
        // A 40-min-old yellow alone would release; the 10-min-old one holds.
        val r = eval(
            input(result = WIN, accuracy = 85.0, avgMoveSec = 20.0),
            session = listOf(yellowAt(40), yellowAt(10)),
            acc = accBaseline(), move = moveBaseline()
        )
        assertEquals(PIVOT, r.outputState)
        assertTrue(r.hysteresisHeld)
    }

    @Test
    fun red_overrides_hysteresis() {
        val r = eval(
            input(result = WIN, sessionMins = 121),
            session = listOf(yellowAt(5))
        )
        assertEquals(TERMINATE, r.outputState)
        assertFalse(r.hysteresisHeld)
        assertTrue(r.redRules.contains("RULE_1_TIME_ON_TASK"))
    }

    @Test
    fun yellow_rules_override_hysteresis_branch() {
        val r = eval(
            input(result = WIN, sessionMins = 95),
            session = listOf(yellowAt(5))
        )
        assertEquals(PIVOT, r.outputState)
        assertFalse(r.hysteresisHeld)
        assertEquals("RULE_1_TIME_ON_TASK", r.reason)
    }

    @Test
    fun multiple_yellow_rules_join_reason() {
        // Fatigue (95 min) + second consecutive loss → both rules in Yellow.
        val r = eval(
            input(result = LOSS, sessionMins = 95),
            session = lossesBefore(1)
        )
        assertEquals(PIVOT, r.outputState)
        assertEquals(2, r.yellowRules.size)
        assertEquals("RULE_1_TIME_ON_TASK + RULE_2_LOSS_STREAK", r.reason)
    }

    // ── Baseline statistics ─────────────────────────────────────────────────

    @Test
    fun baselineOf_computes_mean_and_sample_sd() {
        val b = E.baselineOf(listOf(10.0, 20.0, 30.0), sdFloor = 1.0)!!
        assertEquals(20.0, b.mean, 0.0)
        assertEquals(10.0, b.sd, 1e-9) // sample SD: sqrt((100+0+100)/2)
        assertEquals(3, b.sampleSize)
    }

    @Test
    fun baselineOf_empty_is_null() {
        assertNull(E.baselineOf(emptyList(), sdFloor = 1.0))
    }

    @Test
    fun baselineOf_applies_sd_floor() {
        val b = E.baselineOf(listOf(5.0, 5.0, 5.0), sdFloor = 1.0)!!
        assertEquals(1.0, b.sd, 0.0)
    }

    @Test
    fun baselineOf_caps_at_window() {
        val history = (1..150).map { 10.0 }
        val b = E.baselineOf(history, sdFloor = 1.0)!!
        assertEquals(E.BASELINE_WINDOW, b.sampleSize)
        assertEquals(10.0, b.mean, 0.0)
    }

    @Test
    fun baseline_ready_requires_min_samples() {
        assertFalse(Baseline(20.0, 4.0, E.MIN_BASELINE_SAMPLES - 1).ready)
        assertTrue(Baseline(20.0, 4.0, E.MIN_BASELINE_SAMPLES).ready)
    }

    // ── PGN clock parsing ───────────────────────────────────────────────────

    @Test
    fun parseClocks_reads_all_plies_with_fractions() {
        val pgn = "1. e4 {[%clk0:10:00]} d5 {[%clk0:09:58.5]}2. exd5 {[%clk0:09:55]}"
        assertEquals(listOf(600.0, 598.5, 595.0), E.parseClocks(pgn))
    }

    @Test
    fun parseClocks_empty_without_annotations() {
        assertTrue(E.parseClocks("1. e4 e5 2. Nf3 Nc6").isEmpty())
    }

    @Test
    fun avgSecondsPerMove_white_with_increment() {
        // White clocks 295/290/288 from a 300+2 control:
        // (300−295+2), (295−290+2), (290−288+2) → 7, 7, 4 → avg 6.0.
        val pgn = "1. e4 {[%clk0:04:55]} d5 {[%clk0:04:52]}" +
            "2. Nf3 {[%clk0:04:50]} Bg4 {[%clk0:04:48]}3. Be2 {[%clk0:04:48]}"
        assertEquals(6.0, E.avgSecondsPerMove(pgn, true, 300.0, 2.0)!!, 1e-9)
    }

    @Test
    fun avgSecondsPerMove_black_side() {
        // Black clocks 292/288: (300−292+2), (292−288+2) → 10, 6 → avg 8.0.
        val pgn = "1. e4 {[%clk0:04:55]} d5 {[%clk0:04:52]}" +
            "2. Nf3 {[%clk0:04:50]} Bg4 {[%clk0:04:48]}3. Be2 {[%clk0:04:48]}"
        assertEquals(8.0, E.avgSecondsPerMove(pgn, false, 300.0, 2.0)!!, 1e-9)
    }

    @Test
    fun avgSecondsPerMove_null_below_two_readings() {
        assertNull(E.avgSecondsPerMove("1. e4 {[%clk0:09:55]}", true, 600.0))
        assertNull(E.avgSecondsPerMove("1. e4 {[%clk0:09:55]}", false, 600.0))
    }

    @Test
    fun avgSecondsPerMove_clamps_gained_time_to_zero() {
        // White clock jumps 100 → 150 (lag compensation): the −50 s is clamped.
        val pgn = "1. e4 {[%clk0:01:40]} e5 {[%clk0:04:00]}2. d4 {[%clk0:02:30]}"
        assertEquals(100.0, E.avgSecondsPerMove(pgn, true, 300.0, 0.0)!!, 1e-9)
    }

    @Test
    fun incrementSeconds_parses_time_control() {
        assertEquals(2.0, E.incrementSeconds("180+2"), 0.0)
        assertEquals(0.0, E.incrementSeconds("600"), 0.0)
    }

    // ── Game → input mapping ────────────────────────────────────────────────

    private val PGN_WITH_CLOCKS =
        "1. e4 {[%clk0:09:55]} e5 {[%clk00:09:50]}2. Nf3 {[%clk0:09:45]} " +
            "Nc6 {[%clk0:09:40]}3. Bb5 {[%clk0:09:35]} " +
            // Plies past move 3 carry no [%clk] tags — they only push the last
            // move number past 9 so the game is NOT flagged as a short game.
            "4. c3 Nf6 5. O-O Be7 6. Re1 O-O 7. Bxc6 dxc6 8. Nb1 Nd7 " +
            "9. Nbd2 Nb6 10. Nf1 Nc4"

    private fun detail(
        rated: Boolean = true,
        rules: String = "chess",
        timeControl: String = "600",
        whiteUsername: String = "twain",
        blackUsername: String = "opponent",
        whiteResult: String = "win",
        blackResult: String = "checkmated",
        whiteAccuracy: Double? = 82.5,
        blackAccuracy: Double? = 71.0,
        pgn: String = PGN_WITH_CLOCKS,
        whiteRating: Int = 1500,
        blackRating: Int = 1500
    ): ChessComGameDetail = ChessComGameDetail(
        gameId = 1L,
        url = "",
        rated = rated,
        rules = rules,
        timeClass = "rapid",
        timeControl = timeControl,
        endTime = 0L,
        whiteUsername = whiteUsername,
        whiteRating = whiteRating,
        whiteResult = whiteResult,
        blackUsername = blackUsername,
        blackRating = blackRating,
        blackResult = blackResult,
        whiteAccuracy = whiteAccuracy,
        blackAccuracy = blackAccuracy,
        pgn = pgn
    )

    @Test
    fun inputFrom_rejects_unrated() {
        assertTrue(E.inputFrom(detail(rated = false), "twain", 0.0, 14) is MappingV2.NotAuditable)
    }

    @Test
    fun inputFrom_rejects_variants() {
        assertTrue(E.inputFrom(detail(rules = "kingofthehill"), "twain", 0.0, 14) is MappingV2.NotAuditable)
    }

    @Test
    fun inputFrom_rejects_daily() {
        assertTrue(E.inputFrom(detail(timeControl = "1/86400"), "twain", 0.0, 14) is MappingV2.NotAuditable)
    }

    @Test
    fun inputFrom_rejects_user_not_in_game() {
        assertTrue(E.inputFrom(detail(), "someoneelse", 0.0, 14) is MappingV2.NotAuditable)
    }

    @Test
    fun inputFrom_maps_white_game_fully() {
        val m = E.inputFrom(detail(), "twain", sessionMinutesBefore = 8.0, localHour = 21)
        assertTrue(m is MappingV2.Ready)
        m as MappingV2.Ready
        assertEquals(ChessPhase2Engine.TimeControl.RAPID, m.input.timeControl)
        assertEquals(WIN, m.input.result)
        assertEquals(82.5, m.input.accuracy!!, 0.0)
        // White clocks 595/585/575 from 600 base: (5+10+10)/3 ≈ 8.33 s/move.
        assertEquals(8.33, m.input.avgMoveSec!!, 0.01)
        assertEquals(18, m.input.sessionElapsedMins) // 8 before + 10 base clock
        assertEquals(21, m.input.localHour)
        assertFalse(m.input.shortGame)
        assertTrue(m.accuracyKnown)
        assertTrue(m.moveTimeKnown)
        assertEquals(10.0, m.estimatedMinutes, 0.0)
        assertEquals(0.5, m.deltaE, 0.001) // 1500 vs 1500, win → 1.0 − 0.5
    }

    @Test
    fun inputFrom_maps_black_side_correctly() {
        val m = E.inputFrom(detail(), "opponent", sessionMinutesBefore = 0.0, localHour = 9)
        assertTrue(m is MappingV2.Ready)
        m as MappingV2.Ready
        assertEquals(LOSS, m.input.result) // "checkmated"
        assertEquals(71.0, m.input.accuracy!!, 0.0)
        // Black clocks 590/580 from 600 base: (10+10)/2 = 10 s/move.
        assertEquals(10.0, m.input.avgMoveSec!!, 0.01)
        assertEquals(10, m.input.sessionElapsedMins)
        assertEquals(-0.5, m.deltaE, 0.001) // 1500 vs 1500, loss → 0 − 0.5
    }
}

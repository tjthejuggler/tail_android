package com.example.tail.widget

import com.example.tail.data.chess.ChessReadinessEngine
import java.time.Instant
import java.time.ZoneId

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness V4 — the "Green Light" system
 * ════════════════════════════════════════════════════════════════════════
 *
 * Spec: the mission-#1-validated decision layer (TailCue docs/
 * chess-readiness-v2.md, phone port 2026-10-21). GREEN BY DEFAULT:
 * announcing intent to play usually ends in an INSTANT green light with NO
 * pre-game test — the system is a gatekeeper, not a toll booth. Missing
 * data NEVER adds concern (no signal → no concern), which alone guarantees
 * mostly-green days.
 *
 * Only VALIDATED signals may gate (each weight is a mission finding).
 * Explicit nulls (coding dose, lifting, meals, bedtime timing, RHR,
 * prior-day rating Δ, sleep budget, Bullet session clock-time columns) are
 * deliberately absent. HRV vs baseline is fragile → TIEBREAKER ONLY: it
 * may push concern 2→3 but never gates alone.
 *
 * Decision rule (concern = sum of weights, 0–6):
 *  - 0–1  → GREEN — instant light, no test
 *  - 2    → YELLOW — reflex gate: 5 taps, pass at median ≤ threshold ms
 *           (starting default 320 ms; personalised later from the event log)
 *  - ≥3   → RED-lean — strong advisory + survival gate sized by concern
 *           (8 puzzles at 3, 10 at 4, 12 at 5+ — user rule 2026-10-24:
 *           the old 5-puzzle battery told us hardly anything; pass bar
 *           is the strict full target, zero strikes). Override ALWAYS
 *           allowed and logged.
 *
 * In-session watch (never forces): cold-open stop (first 10 games ≤40%
 * score — the mission's one confirmed ship rule, −7.6…−22 Elo) and tilt
 * watch (≥3 consecutive losses — 37% vs 64% next-game score).
 *
 * Verdict mapping into the SHARED v1 traffic-light system so Chess Guard
 * enforcement, the colour system and the Phase-2 audit pipeline keep
 * working unchanged:
 *  - instant green / gate pass / override → GREEN_LIGHT
 *  - reflex gate fail     → RED_LIGHT (60-min rest via synthetic ccrs)
 *  - survival gate fail   → RED_LIGHT
 *
 * Pure Kotlin (java.time only) — no Android dependencies, unit-testable.
 */
object ChessReadinessV4Engine {

    // ── Decision thresholds (green-biased) ────────────────────────────────

    /** Concern ≤ this → instant GREEN, no test. */
    const val GREEN_MAX = 1

    /** Concern == this → reflex gate (5 taps). */
    const val REFLEX_AT = 2

    /** Concern ≥ this → RED-lean + survival gate sized by score. */
    const val SURVIVAL_AT = 3

    // ── Reflex gate config ────────────────────────────────────────────────

    /** Number of reaction taps in the reflex gate (~15 s). */
    const val REFLEX_TAPS = 5

    /** Starting pass threshold (median ms); personalisable from outcomes. */
    const val DEFAULT_REFLEX_PASS_MS = 320

    // ── Inputs / outputs ──────────────────────────────────────────────────

    /**
     * The state-knowable inputs. EVERY field may be missing — a null/absent
     * input simply omits its signal (no penalty, keeps days green).
     */
    data class V4Inputs(
        /** Prior-night awake minutes (Garmin-validated predictor). Null = unknown. */
        val awakeMinLastNight: Int? = null,
        /** Current local hour (0–23) — time-of-day is a first-class input. */
        val hour: Int,
        /** Whether a coffee habit entry was logged today. */
        val coffeeToday: Boolean = false,
        /** Rated Blitz games played YESTERDAY (null = archive unknown). */
        val yesterdayBlitzGames: Int? = null,
        /**
         * HRV vs 7-day baseline in PERCENT (e.g. −18.0). Null = unknown.
         * Tiebreaker only — see [assess].
         */
        val hrvVsBaselinePct: Double? = null,
        /** Chess category about to be played ("Blitz" enables the Blitz-only signals). */
        val category: String = "Blitz"
    )

    /** One validated negative-state signal with its mission evidence. */
    data class Signal(
        val id: String,
        val label: String,
        val weight: Int,
        val evidence: String,
        /** True for the fragile HRV signal — can never gate alone. */
        val tiebreaker: Boolean = false
    )

    /** The exceptional gate a non-green assessment demands. */
    data class Gate(
        /** "reflex" or "survival". */
        val type: String,
        /** Reflex: number of taps. */
        val taps: Int = 0,
        /** Reflex: pass threshold in ms (median). */
        val passMs: Int = 0,
        /** Survival: number of puzzles (5 at concern 3, 10 at 4+). */
        val puzzles: Int = 0,
        val why: String
    )

    enum class Level(val headline: String) {
        GREEN("Play. No test needed."),
        YELLOW("Quick check, then play."),
        RED("Strong consider resting — but you can override.")
    }

    /** The full assessment: GREEN unless validated signals stack. */
    data class Assessment(
        val generatedAtMs: Long,
        val category: String,
        val level: Level,
        val concern: Int,
        val signals: List<Signal>,
        /** null on GREEN (the common case — zero friction). */
        val gate: Gate?,
        val overrideAllowed: Boolean = true
    )

    /**
     * Runs the concern scoring. Missing data NEVER contributes. The HRV
     * tiebreaker rule: the +1 is only added when the NON-tiebreaker concern
     * is already ≥ 2 — HRV may push 2→3 but never gates alone.
     */
    fun assess(input: V4Inputs, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Assessment {
        val signals = mutableListOf<Signal>()
        val blitz = input.category.equals("Blitz", ignoreCase = true)

        // 1. Prior-night sleep fragmentation (replicated r=−0.264, q=0.014)
        input.awakeMinLastNight?.let { awake ->
            when {
                awake >= 30 -> signals.add(
                    Signal(
                        "awake_high", "fragmented sleep last night ($awake min awake)", 2,
                        "Awake min→score% r=−0.26, q=0.014, n=103 (replicated; #93733)"
                    )
                )
                awake >= 20 -> signals.add(
                    Signal(
                        "awake_mid", "restless sleep last night ($awake min awake)", 1,
                        "Awake min→score% quintile curve 64%→39%"
                    )
                )
            }
        }

        // 2. Time-of-day (first-class directive)
        if (input.hour >= 23 || input.hour < 1) {
            signals.add(
                Signal(
                    "late_hour", "very late hour (worst circadian window, 23:00–01:00)", 2,
                    "v4 circadian ACPL-z: worst hours 23:00/00:00"
                )
            )
        } else if (input.hour >= 21 && blitz) {
            signals.add(
                Signal(
                    "after21_blitz", "after-21h Blitz window", 1,
                    "after-21h Blitz penalty (#216)"
                )
            )
        }

        // 3. Coffee today → Blitz-only advisory (strongest dietary finding)
        if (input.coffeeToday && blitz) {
            signals.add(
                Signal(
                    "coffee_blitz", "coffee logged today (Blitz-specific cost)", 1,
                    "0-cup +10.4 vs 1-cup −16.2 Elo; deconfounded r=−0.45 (#93762)"
                )
            )
        }

        // 4. Yesterday's heavy Blitz volume (suggestive lag-1)
        if (blitz && input.yesterdayBlitzGames != null && input.yesterdayBlitzGames >= 4) {
            signals.add(
                Signal(
                    "blitz_yesterday", "heavy Blitz yesterday (${input.yesterdayBlitzGames} games)", 1,
                    "lag-1 r=−0.33 after 4–5-game days (#93816, suggestive)"
                )
            )
        }

        // 5. HRV vs 7d baseline — fragile, TIEBREAKER ONLY (never gates alone)
        val nonTiebreakConcern = signals.sumOf { it.weight }
        if (input.hrvVsBaselinePct != null && input.hrvVsBaselinePct <= -15 && nonTiebreakConcern >= 2) {
            signals.add(
                Signal(
                    "hrv_low",
                    "HRV well below baseline (%+.0f%%)".format(input.hrvVsBaselinePct), 1,
                    "HRV screens mostly null; tiebreaker only (mission log)",
                    tiebreaker = true
                )
            )
        }

        val concern = signals.sumOf { it.weight }
        val level = when {
            concern >= SURVIVAL_AT -> Level.RED
            concern == REFLEX_AT -> Level.YELLOW
            else -> Level.GREEN
        }
        return Assessment(
            generatedAtMs = nowMs,
            category = if (blitz) "Blitz" else input.category,
            level = level,
            concern = concern,
            signals = signals,
            gate = gateFor(concern)
        )
    }

    /**
     * The gate for a concern score, or null (green = no friction).
     *
     * Survival battery sizing (user rule 2026-10-24): a 5-puzzle battery
     * was trivially passable even in no shape to play rated games — the
     * minimum became 8, rising to 10 at concern 4 and 12 at concern 5+.
     * The pass bar is the FULL target with zero strikes (see the bubble's
     * startSurvivalRun: v4-armed gates ignore the percentile discount), so
     * the gate measures sustained accuracy, not luck.
     */
    fun gateFor(concern: Int, reflexPassMs: Int = DEFAULT_REFLEX_PASS_MS): Gate? = when {
        concern <= GREEN_MAX -> null
        concern == REFLEX_AT -> Gate(
            type = "reflex", taps = REFLEX_TAPS, passMs = reflexPassMs,
            why = "a couple of mild flags — 15-second tap test, then play"
        )
        else -> Gate(
            type = "survival",
            puzzles = when {
                concern == SURVIVAL_AT -> 8
                concern == 4 -> 10
                else -> 12
            },
            why = "several negative state signals stacked — puzzle battery first " +
                "(full target, zero strikes)"
        )
    }

    /** Reflex gate verdict: median of the tap times ≤ threshold. */
    fun reflexPassed(tapMs: List<Long>, passMs: Int = DEFAULT_REFLEX_PASS_MS): Boolean =
        medianTapMs(tapMs)?.let { it <= passMs } ?: false

    /** Median of the completed tap samples (null when none completed). */
    fun medianTapMs(tapMs: List<Long>): Double? {
        if (tapMs.isEmpty()) return null
        val sorted = tapMs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble()
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    // ── Verdict → shared v1 system mapping ─────────────────────────────────

    /** v1-compatible traffic-light state name (what Chess Guard reads). */
    fun stateNameFor(passed: Boolean): String =
        if (passed) ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name
        else ChessReadinessEngine.ReadinessState.RED_LIGHT.name

    /**
     * Synthetic CCRS for the SHARED history record. A pass (or an override)
     * authorizes rated play like any GREEN (85 — the v3 pass grade). A
     * failed gate uses 45: solidly RED, a 60-minute rest before re-test.
     */
    fun syntheticCcrs(passed: Boolean): Int = if (passed) 85 else 45

    // ── In-session stop-rule watch (never forces — display only) ───────────

    /** One rated game today, tagged with its time-control category. */
    data class SessionGame(val category: String, val won: Boolean)

    /** Live today-session stats + cold-open/hot/tilt warnings. */
    data class SessionWatch(
        val gamesToday: Int,
        /** Score % across today's games (win = 1, draw/loss = 0 in the phone log). */
        val scorePct: Double,
        val lossStreak: Int,
        val warnings: List<SessionWarning>
    )

    data class SessionWarning(val rule: String, val msg: String, val evidence: String)

    /**
     * The two era-stable in-session rules (TailCue discovery set #34251,
     * #34566, #34266, #33825/#34026, #33827, #34249), refined per format:
     *
     *  1. COLD-OPEN (per category, at exactly 10 games): ≤40% score → the
     *     expected −8…−18 Elo is ALREADY SUNK — stopping after a cold open
     *     saves nothing on average (stop −14.0 vs continue −11.5, p=0.56),
     *     so this is YELLOW info, never "abort". The only evidence-backed
     *     response is Bullet-specific: 2+ Rapid warm-up games. ≥60% at 10
     *     games → HOT (+9…+38 Elo days) — worth extending deliberately.
     *  2. TILT (per category): Blitz flags at 2 straight losses (next-game
     *     50.6%→40.5%, n=37); Chess960 only at 4+ (nothing at 1–3);
     *     Bullet and Rapid NEVER flag (tilt measurably absent — 52.4% after
     *     3-loss streaks; Rapid score RISES after streaks). False-positive
     *     nagging in those formats is the "vague alert" failure mode.
     *
     * Deliberately excluded: volume caps (dose-flat, KW p=0.98, #34249).
     */
    fun sessionWatch(
        games: List<SessionGame>,
        /** Last 10 rated Blitz results across days (dev#38 micro-session twin). */
        blitzRolling10: List<Boolean> = emptyList()
    ): SessionWatch? {
        if (games.isEmpty()) return null
        val warnings = buildList {
            games.groupBy { it.category }.forEach { (cat, catGames) ->
                // 0. Session-position cliffs (TailCue dev#31): Rapid game-2
                //    dip (#25480: game 1 55.7% → game 2 41.0%, then recovery)
                //    and the Chess960 game-5+ fade (#21868) — taper prompts.
                if (cat == "Rapid" && catGames.size == 2 && !catGames[1].won) add(
                    SessionWarning(
                        "Rapid game-2 cliff",
                        "Rapid game 2 lost — score historically dips here (55.7%→41.0%) " +
                            "and recovers after; consider a short pause before game 3.",
                        "#25480 game-index curve; dev#31"
                    )
                )
                if (cat == "Chess960" && catGames.size == 5) add(
                    SessionWarning(
                        "Chess960 game-5 fade",
                        "5 Chess960 games in — the game-5+ fade begins; consider tapering.",
                        "#21868 in-session game-5 cliff; dev#31"
                    )
                )
                // 1. Cold-open / hot classifier at exactly 10 games.
                if (catGames.size == 10) {
                    val scorePct = 100.0 * catGames.count { it.won } / 10
                    if (scorePct <= 40.0) add(
                        SessionWarning(
                            "$cat cold-open",
                            "$cat first 10 at ${scorePct.toInt()}% — the −8…−18 Elo is sunk; " +
                                "stopping saves nothing on average. " +
                                if (cat == "Bullet")
                                    "Optional reset: 2+ Rapid warm-up games (validated for Bullet)."
                                else "Play on or stop as you judge — no abort signal.",
                            "#33825/#34026 (stop saves nothing); Bullet reset #33827/#33491"
                        )
                    ) else if (scorePct >= 60.0) add(
                        SessionWarning(
                            "$cat hot",
                            "$cat first 10 at ${scorePct.toInt()}% — hot days run +9…+38 Elo; " +
                                "worth extending the session deliberately.",
                            "#34251 era-stable cold/hot split"
                        )
                    )
                }
                // 2. Per-format tilt counters (trailing streak in this category).
                var streak = 0
                for (g in catGames.asReversed()) {
                    if (!g.won) streak++ else break
                }
                val flagAt = when (cat) {
                    "Blitz" -> 2    // 50.6% → 40.5% next-game (n=37 — underpowered; watch drift)
                    "Chess960" -> 4 // nothing at 1–3; collapse only at 4+
                    else -> null    // Bullet/Rapid: no tilt flag, ever
                }
                // Blitz cold-open equivalent when today holds only 1–2 games
                // (dev#38: 2026 Blitz days rarely reach a first-10 window).
                if (cat == "Blitz" && catGames.size < 10 && blitzRolling10.size == 10) {
                    val roll = 100.0 * blitzRolling10.count { it } / 10
                    if (roll <= 40.0) add(
                        SessionWarning(
                            "Blitz rolling-10 cold",
                            "Last 10 Blitz games (across days) at ${roll.toInt()}% — the " +
                                "micro-session equivalent of a cold open (≤40%).",
                            "dev#38 / #25682 pooled rolling-10 window"
                        )
                    )
                }
                if (flagAt != null && streak >= flagAt) add(
                    SessionWarning(
                        "$cat tilt",
                        "$streak straight $cat losses — next-game dip risk" +
                            if (cat == "Blitz") " (50.6%→40.5%, n=37 — thin stratum)"
                            else " (39.9% vs 42.4% at 4+, n=469)",
                        "#34566/#34247 (Blitz 2-loss); #34266/#34566 (Chess960 4+)"
                    )
                )
            }
        }
        var streakAll = 0
        for (g in games.asReversed()) {
            if (!g.won) streakAll++ else break
        }
        return SessionWatch(
            gamesToday = games.size,
            scorePct = 100.0 * games.count { it.won } / games.size,
            lossStreak = streakAll,
            warnings = warnings
        )
    }

    /** Local calendar-day key helper for callers computing "today". */
    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}

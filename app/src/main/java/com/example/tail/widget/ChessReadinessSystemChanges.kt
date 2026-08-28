package com.example.tail.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Registry of deliberate changes to the chess readiness SYSTEM rules.
 *
 * Every time a rule of the readiness system changes (scoring, gates,
 * rate limits, audit logic…), append a [ReadinessSystemChange] to [ALL].
 * The "Rating Since Readiness System" chart on the stats screen renders
 * one clickable ◆ marker per entry — tapping it shows what changed and
 * when, so the evolution of the system stays inspectable on-device.
 */
data class ReadinessSystemChange(
    /** When the change took effect (epoch ms, UTC). */
    val timestampMs: Long,
    /** Short title (marker popup header). */
    val title: String,
    /** Full description of what changed and why. */
    val description: String
)

object ChessReadinessSystemChanges {

    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** Parses an ISO-8601 instant, e.g. "2026-08-18T16:38:00Z". */
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Human date of a change in the local zone (popup sub-header). */
    fun dateLabel(change: ReadinessSystemChange): String =
        Instant.ofEpochMilli(change.timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(DATE_FMT)

    /**
     * All system rule changes, oldest first. APPEND ONLY — entries are
     * historical facts and must never be edited or removed.
     */
    val ALL: List<ReadinessSystemChange> = listOf(
        ReadinessSystemChange(
            at("2026-08-28T06:15:00Z"),
            "Audit results show time left · test-required entry notice",
            "Two UX reinforcements. (1) Every post-game audit result screen (v1/v2/v3) " +
                "now always shows how much authorized rated-play time remains in the " +
                "current GREEN window — or an explicit reminder that rated play is NOT " +
                "authorized. (2) Opening the chess app with no valid authorization while " +
                "a new readiness test IS possible (the trust window) now shows a " +
                "full-screen orange notice, once per app stint, telling the user to take " +
                "the readiness test before anything else — mirroring the YELLOW " +
                "casual-play warning."
        ),
        ReadinessSystemChange(
            at("2026-08-18T16:38:00Z"),
            "Readiness v3.0 — adaptive percentile gate",
            "The fixed 85/70 pass bar was replaced by thresholds derived from your own " +
                "recent tests: the GREEN (rated play) and YELLOW (casual play) bars became " +
                "the 60th/35th percentile of the last ≤15 tests in a rolling 21-day window. " +
                "Doing relatively better than usual now authorizes play, and a run of weak " +
                "tests lowers the bar instead of locking you out. Scoring also gained " +
                "fine-grained tiers: sleep (6 tiers from the raw Garmin score), clarity " +
                "(6 tiers), rated puzzles (7 speed tiers — quickness matters), and Puzzle " +
                "Rush (6 ratio bands with a 3-pt strike penalty). Safety rails: absolute " +
                "cutoffs (score ≥ 80 is ALWAYS Green, ≥ 55 always Yellow — the adaptive bar " +
                "can never rise above them) and floors (Green ≥ 45, Yellow ≥ 30 so a slump " +
                "can't erode the gate); with fewer than 5 recent tests a gentle cold-start " +
                "default (75/55) applies."
        ),
        ReadinessSystemChange(
            at("2026-08-18T19:21:00Z"),
            "Phase 2 audit v2.0 — evidence-weighted & adaptive",
            "A single ordinary loss no longer ends the session. The severe/moderate ΔE " +
                "bars became the p10/p25 percentiles of your own last ≤15 audited games " +
                "(clamped so they can never tighten past −0.45/−0.15 or loosen past " +
                "−0.75/−0.50). Games now accumulate 0–100 strain (severe-for-you ΔE = 50, " +
                "moderate = 25, accuracy-drop violation +25, unforced-blunder violation " +
                "+25): one bad game can at most PIVOT (pause rated play) — TERMINATION " +
                "requires the session total to cross 100. A strong pre-game readiness test " +
                "(CCRS ≥ 85) raises the termination bar by up to 30 points and can absorb " +
                "one moderate-or-severe dip in an otherwise clean session entirely. Hard " +
                "cutoffs kept: ΔE ≤ −0.75 (losing to a far weaker opponent), a game with " +
                "result + accuracy + blunders ALL bad, and the 60-minute capacity ceiling " +
                "still terminate immediately."
        ),
        ReadinessSystemChange(
            at("2026-08-20T12:30:00Z"),
            "Daily test cap raised 4 → 8",
            "The rolling 24-hour limit on readiness test attempts was doubled from 4 to 8, " +
                "allowing faster iteration during the day. The 60-minute cool-down after a " +
                "Green/Yellow result and the scaled recovery locks after a Red test " +
                "(30/60/120 minutes depending on how poor the attempt was) are unchanged."
        ),
        ReadinessSystemChange(
            at("2026-08-23T16:30:00Z"),
            "Readiness v3.1 — form-relative scoring, 70% target, calibrated 10-point survey",
            "Three linked fixes. (1) The objective sub-scores now measure FORM against your " +
                "own recent baselines: rated-puzzle points come from the ratio of today's " +
                "average solve time to your recent average (a rising chess.com puzzle rating " +
                "serves harder puzzles and was mechanically eroding the absolute speed " +
                "tiers), and Puzzle Rush is scored against the median of your recent runs " +
                "instead of the ever-ratcheting all-time high. (2) The Green bar moved from " +
                "the 60th to the 30th percentile of your recent tests — targeting a ~70% " +
                "Green pass rate (previously ~40% by design) — and Yellow from the 35th to " +
                "the 12th; the absolute cutoffs (80/55) and floors (45/30) are unchanged. " +
                "(3) The self-survey became a 10-point scale (stored 1–5 answers were " +
                "migrated ×2) and its influence is now EARNED: how much the survey counts " +
                "depends on how accurately it has matched your actual puzzle + rush results " +
                "over the last ≤20 tests (mean |survey − objective| gap). Accurate reports " +
                "— good or bad — carry full weight; inflated or noisy ones are shrunk " +
                "toward the level your objective results support, so over-rating yourself " +
                "never buys clarity points."
        ),
        ReadinessSystemChange(
            at("2026-08-23T17:06:00Z"),
            "Readiness v3.1.1 — pass-rate target corrected to ~30% and made user-adjustable",
            "The v3.1 Green bar (30th percentile, ~70% pass rate) was built on a " +
                "mis-stated target — the user actually wants to be allowed to play only " +
                "~30% of the time, passing only at the top of their form. The Green bar " +
                "moved to the 70th percentile of the recent window and Yellow to the 45th " +
                "(~55% Yellow-or-better, ~45% Red). The pass-rate target is now a SETTING " +
                "(Settings → ♟ Chess Readiness → Green Pass-Rate Target, 5–95%, default " +
                "30%): the Green bar sits at the (1 − target) percentile of the recent " +
                "window and Yellow trails it by 25 percentile points. Changing it requires " +
                "a confirm step that shows how long the current target has been held and " +
                "how many tests and games were logged under it — frequent changes make it " +
                "impossible to see how the rating responds to a given pass rate. The " +
                "absolute cutoffs (80/55) and floors (45/30) are unchanged, so the gate " +
                "still cannot ratchet above 80 or erode below 45."
        ),
        ReadinessSystemChange(
            at("2026-08-24T12:38:58Z"),
            "Readiness V2 created — neurobiological pre-game gate (phase 1)",
            "Commit 011add59 (\"chess readiness v2 created, only phase 1 pre-game test\"), " +
                "authored 24 Aug 2026 at 14:38:58 CEST. A second, independent pre-game test " +
                "system went live: overnight autonomic recovery (lnRMSSD and resting-heart-rate " +
                "Z-scores), cognitive-load balance (ACWR) and a 3-minute PVT-B vigilance/reflex " +
                "test replace the v1 survey + puzzle diagnostic, with the worst module deciding " +
                "a three-tier verdict (TIER 1 = pass / rated play, TIER 2 = casual only, " +
                "TIER 3 = locked out). The engine is selectable per version in Settings and " +
                "keeps its own log, so v1 history stays byte-for-byte intact."
        ),
        ReadinessSystemChange(
            at("2026-08-24T14:53:19Z"),
            "Readiness V2 stats added",
            "Commit a5c058e8 (\"ss readiness v2 stats added\"), authored 24 Aug 2026 at " +
                "16:53:19 CEST. The Readiness Stats screen gained dedicated V2 sections: the " +
                "pre-game gate telemetry (verdict tiers, PVT-B response time / lapses / false " +
                "starts over time, passive-module averages) and the Phase 2 v2 post-game audit " +
                "(verdict distribution, accuracy, Elo delta, strain, session minutes)."
        ),
        ReadinessSystemChange(
            at("2026-08-25T08:40:00Z"),
            "V2 autonomic gate made asymmetric — HRV elevation no longer restricts",
            "The lnRMSSD Z-score used to gate two-sidedly (|Z| bands), which restricted rated " +
                "play when HRV ran ABOVE the personal baseline. That punished recovery: during " +
                "injury comeback the rolling 30-day baseline is dragged down by injury-era " +
                "readings, so HRV merely returning to its pre-injury level registered as a " +
                "deviation (a +0.53σ trigger on 25 Aug 2026 came from exactly this). The gate " +
                "is now asymmetric, matching the evidence: suppression still gates (−0.5σ → " +
                "Tier 2, ≤ −1.5σ → Tier 3), while elevation is informational up to +1.5σ and " +
                "a large spike (≥ +1.5σ, the weakly-evidenced pre-illness \"paradoxical " +
                "surge\" pattern) caps at Tier 2 — never a lockout. Same philosophy as the " +
                "one-sided RHR and ACWR rules: only gate on the side the science supports."
        ),
        ReadinessSystemChange(
            at("2026-08-25T10:10:00Z"),
            "Phase 2 audit v3.0 — hybrid verdicts with desktop Stockfish blunder evidence",
            "The post-game audit became a hybrid of the v1 strain accumulator and the v2 " +
                "research rules, now backed by REAL move-quality data: after each rated game " +
                "the phone asks the PC bridge, which runs its own standalone Stockfish " +
                "analysis (~2 s/game, SQLite dedup by chess.com game id — a game is never " +
                "analysed twice, and games are evaluated the moment they arrive, in any " +
                "order). Six rules decide Green/Yellow/Red: (1) readiness-scaled session " +
                "fatigue — 90 min to Yellow / 120 to Red, lifted by +15/+30 min when the " +
                "pre-game CCRS was ≥75/≥85; (2) ΔE-weighted loss streak — losses as the " +
                "favorite (expected score > 0.5) weigh 1.5, even matchups 1.0, underdog " +
                "losses 0.5, Yellow at ≥2.0 and Red at ≥3.0 weighted points, a single loss " +
                "never flags; (3) the v2 tilt vector (accuracy/pace Z-scores + circadian " +
                "window); (4) the v2 ACWR load-balance rule; (5) the v1 strain accumulator " +
                "(personal-percentile ΔE floors, severe = 50 / moderate = 25 points, " +
                "accuracy and unforced-blunder violations +25 each, termination at 100 + a " +
                "readiness buffer, catastrophic ΔE ≤ −0.75 hard cutoff, one-dip forgiveness " +
                "when CCRS ≥ 85 and the session is otherwise clean); (6) hysteresis — Yellow " +
                "holds until a win/draw with the deficit Z back ≤ 0 plus 15 minutes. The " +
                "blunder rule only gates when the Stockfish data actually arrived: away " +
                "from the PC every other rule still evaluates and the verdict is marked " +
                "engine-less. Selectable as 'v3' in Settings → Chess, with v1/v2 history " +
                "kept intact."
        )
    )
}

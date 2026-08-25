package com.example.tail.data

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Puzzle Rush — standalone timer sessions (pure computation layer)
 * ════════════════════════════════════════════════════════════════════════
 *
 * The v1 readiness test used to be the only source of Puzzle Rush data
 * (score + strikes reported in its step-4 wizard). Since the pre-game
 * system switched to v2 — which has no puzzle component — the rush runs
 * moved to the habit-timer widget: the habit linked as "Puzzle Rush
 * habit" in Settings is now an official Puzzle Rush timer, and every
 * finished timer session asks the user to report how it went (and
 * whether they reviewed the puzzles they got wrong).
 *
 * Those reports are persisted by
 * [com.example.tail.widget.ChessReadinessLogStore] alongside the
 * readiness telemetry and merged into the same "Puzzle Rush" section of
 * the Chess Stats screen by [mergeRushSeries].
 */

/** Where a [RushScorePoint] came from. */
object RushSource {
    /** Rush run reported inside a v1 readiness test (step 4 of the wizard). */
    const val TEST = "TEST"

    /** Rush run reported at the end of a standalone Puzzle Rush timer session. */
    const val TIMER = "TIMER"
}

/**
 * One standalone Puzzle Rush timer session, as reported by the user when
 * the timer stopped. All timestamps are epoch millis.
 */
data class PuzzleRushSessionRecord(
    /** When the result was reported (session end). */
    val timestamp: Long,
    /** When the timer was started. */
    val startedAt: Long,
    /** Wall-clock length of the timer session in seconds. */
    val durationSec: Long,
    /** Puzzles solved in the run. */
    val score: Int,
    /** Strikes (failures) — three wrong moves end a rush run early. */
    val strikes: Int,
    /** True when the user reviewed the puzzles they got wrong. */
    val reviewedWrong: Boolean,
    /** All-time-high rush baseline in effect at session time. */
    val allTimeHigh: Int
)

/**
 * Chronological series of standalone Puzzle Rush timer sessions, mapped
 * onto the shared chart-point model. Sessions with score 0 (aborted /
 * skipped reports) are skipped — same rule the readiness-test series
 * applies.
 */
fun computeRushSessionPoints(
    sessions: List<PuzzleRushSessionRecord>
): List<RushScorePoint> = sessions
    .filter { it.score > 0 }
    .sortedBy { it.timestamp }
    .map {
        RushScorePoint(
            timestampMs = it.timestamp,
            score = it.score,
            strikes = it.strikes,
            allTimeHigh = it.allTimeHigh,
            isNewHigh = it.allTimeHigh > 0 && it.score >= it.allTimeHigh,
            // Standalone runs have no readiness context.
            state = "",
            ccrs = 0,
            source = RushSource.TIMER,
            reviewedWrong = it.reviewedWrong,
            durationSec = it.durationSec
        )
    }

/**
 * Merges the rush points of readiness tests with those of standalone
 * timer sessions into one chronological series for the stats screen.
 */
fun mergeRushSeries(
    testPoints: List<RushScorePoint>,
    sessionPoints: List<RushScorePoint>
): List<RushScorePoint> =
    (testPoints + sessionPoints).sortedBy { it.timestampMs }

/**
 * Share (0–100) of Puzzle Rush timer sessions whose wrong puzzles were
 * reviewed, among sessions that reported the review answer. Null when no
 * timer session has review data yet (readiness-test rush runs never do).
 */
fun rushReviewRate(points: List<RushScorePoint>): Double? {
    val reported = points.filter { it.source == RushSource.TIMER && it.reviewedWrong != null }
    if (reported.isEmpty()) return null
    return reported.count { it.reviewedWrong == true } * 100.0 / reported.size
}

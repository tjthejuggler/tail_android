package com.example.tail.data.chess

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Rolling rated-play window — the ONE authorization model for rated play,
 *  shared by the enforcement engine (ChessPhase2Engine / ChessGuardPenalty
 *  delegate here) and the stats layer (ChessReadinessStatsCalculator), so
 *  the charts can never disagree with the penalties the system applied.
 * ════════════════════════════════════════════════════════════════════════
 *
 * History of the model (user rules):
 *  - launch … 2026-09-07: a GREEN test authorized a FLAT 60-minute window
 *    ([ChessReadinessEngine.SESSION_VALIDITY_MS] — still used for casual
 *    play and the re-test cool-down, no longer for rated authorization).
 *  - 2026-09-07: the hardcoded 1-hour rated limit was replaced by an idle
 *    gap (30 minutes of real no-play time closed the window).
 *  - 2026-09-12: the gap was tightened 30 → 10 minutes.
 *  - 2026-09-17: the gap was raised to 15 minutes AND the idle clock now
 *    re-anchors on every rated game ACTUALLY PLAYED whose start was still
 *    inside the window — the gap is REAL no-play time (game end → next
 *    game start), so continuous play keeps the window open indefinitely
 *    and a late-arriving audit can never make it look idle.
 *
 * The stats charts used the RETIRED flat-60-minute rule evaluated at game
 * END, which labeled games of legitimate continuous sessions (and games
 * that merely ENDED after the flat hour) as "no fresh test" violations
 * even though the guard had authorized them at play start — the
 * discrepancy fixed by routing every consumer through this file.
 */

/** Minutes of REAL no-play time that close an otherwise-live window. */
const val RATED_IDLE_CLOSE_MINUTES = 15L

/** Shared Phase 2 audit verdict that keeps the rolling window alive. */
const val AUDIT_CONTINUE_RATED = "CONTINUE_RATED"

/**
 * Expiry of the rolling rated-play window opened by a GREEN_LIGHT test.
 *
 * @param greenTestMs timestamp of the authorizing GREEN_LIGHT test
 * @param audits      (timestamp, output-state name) pairs filed after the
 *                    test and at/before [now], any order — a non-CONTINUE
 *                    verdict revokes rated play until the next GREEN test,
 *                    every CONTINUE verdict re-anchors the idle clock
 * @param now         evaluation instant
 * @param games       (startMs, endMs) spans of rated games played after the
 *                    test and finished at/before [now], any order — a game
 *                    that BEGAN while the window was still live extends it
 *                    to that game's end
 * @param idleCloseMs real no-play time that closes the window
 * @return the epoch-ms expiry of the window, or null when revoked or
 *         already expired
 */
fun rollingWindowExpiresAt(
    greenTestMs: Long,
    audits: List<Pair<Long, String>>,
    now: Long,
    games: List<Pair<Long, Long>> = emptyList(),
    idleCloseMs: Long = RATED_IDLE_CLOSE_MINUTES * 60_000
): Long? {
    val inWindow = audits.filter { it.first in greenTestMs..now }
    // A Yellow/Red audit since the authorization revokes rated play.
    if (inWindow.any { it.second != AUDIT_CONTINUE_RATED }) return null
    // Chain of window-extending evidence: every clean audit re-anchors
    // the idle clock to its instant, then each played game whose start was
    // still inside the live window extends the anchor to that game's end —
    // continuous play never counts as idleness.
    var anchor = greenTestMs
    inWindow.forEach { anchor = maxOf(anchor, it.first) }
    games.sortedBy { it.first }
        .filter { it.first in greenTestMs..now && it.second <= now }
        .forEach { (start, end) ->
            if (start < anchor + idleCloseMs) anchor = maxOf(anchor, end)
        }
    return if (now - anchor >= idleCloseMs) null
    else anchor + idleCloseMs
}

/** Estimated start of a logged game (the log persists no PGN start). */
fun ReadinessGameRecord.estimatedStartMs(): Long =
    endTimeMs - (minutes * 60_000).toLong()

/** Why a game was (not) authorized at the moment play began. */
enum class AuthorizationKind {
    /** Play began inside a live rolling GREEN window. */
    AUTHORIZED,
    /** A fresh test was actively denying rated play at the start. */
    DENIED,
    /** The latest test's authorization had expired — the system was bypassed. */
    EXPIRED,
    /** No readiness test existed yet at all. */
    NO_TEST
}

/**
 * Classifies one game for the stats charts from its STORED record.
 *
 * The stored [ReadinessGameRecord.authorized] flag was resolved at the
 * moment play BEGAN by the rule in force at the time ([gameToRecord] →
 * [rollingWindowExpiresAt] today; the era's own rule for historical
 * entries) — the SAME verdict the guard applied when deciding penalties.
 * The charts must trust it: replaying today's rule over months-old games
 * would re-judge history (labeling sessions the system had authorized as
 * "no fresh test" violations — the 11/13/15 Sep false-red bars this file
 * exists to fix).
 *
 * Only the DENIED vs EXPIRED split is derived (the stored record keeps the
 * covering test's state but not its freshness): a Yellow/Red test denies
 * only inside its 60-minute validity — afterwards the system was bypassed.
 */
fun gameAuthorizationKind(
    g: ReadinessGameRecord,
    tests: List<ReadinessTestRecord>
): AuthorizationKind {
    if (g.authorized) return AuthorizationKind.AUTHORIZED
    val start = g.estimatedStartMs()
    val latest = tests
        .filter { it.timestamp <= start }
        .maxByOrNull { it.timestamp } ?: return AuthorizationKind.NO_TEST
    return when (latest.state) {
        ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name ->
            AuthorizationKind.EXPIRED // GREEN had gone stale by play start
        else ->
            // Yellow/Red: DENIED while fresh, bypassed once stale.
            if (start - latest.timestamp <= ChessReadinessEngine.SESSION_VALIDITY_MS)
                AuthorizationKind.DENIED
            else AuthorizationKind.EXPIRED
    }
}

/** [gameAuthorizationKind] for every game, keyed by estimated start. */
fun authorizationKinds(
    games: List<ReadinessGameRecord>,
    tests: List<ReadinessTestRecord>
): Map<Long, AuthorizationKind> =
    games.associate { it.estimatedStartMs() to gameAuthorizationKind(it, tests) }

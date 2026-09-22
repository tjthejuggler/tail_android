package com.example.tail.data.chess

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Freeplay settlement — performance-based credit accounting
 * ════════════════════════════════════════════════════════════════════════
 *
 *  A weekly freeplay is spent PROVISIONALLY when granted. Once the session
 *  is COMPLETE, it is settled by its result: if the NET rating change
 *  across the rated games played in the authorization window is ≥ +1, the
 *  credit is refunded; otherwise it stays spent. Pure computation only —
 *  persistence lives in [com.example.tail.widget.ChessFreeplayStore].
 *
 *  "Session complete" mirrors the post-game audit's own idle-close
 *  semantics ([ChessPhase2Engine.RATED_IDLE_CLOSE_MINUTES] — a session
 *  with no game for this many minutes is closed): a session settles when
 *  EITHER 15 idle minutes have passed since its last game OR the
 *  60-minute authorization window expired — whichever comes first. A
 *  window with no games settles only at window expiry.
 */

/** Idle time after the session's last game that closes it for settlement. */
const val FREEPLAY_SETTLE_IDLE_MS: Long = 15L * 60 * 1000

/** Threshold: net rating gain at/above this refunds the credit. */
const val FREEPLAY_REFUND_NET_RATING = 1

/**
 * True when the freeplay window starting at [freeplayTs] is ready to
 * settle at [nowMs]: the window expired, OR its last game ended at least
 * [FREEPLAY_SETTLE_IDLE_MS] ago (session complete, idle-closed).
 */
fun freeplaySessionSettleAt(
    freeplayTs: Long,
    lastGameEndMs: Long?,
    validityMs: Long = ChessReadinessEngine.SESSION_VALIDITY_MS
): Long {
    val windowEnd = freeplayTs + validityMs
    return if (lastGameEndMs != null)
        minOf(windowEnd, lastGameEndMs + FREEPLAY_SETTLE_IDLE_MS)
    else windowEnd
}

/**
 * Net rating change over one freeplay session, summed per (variant ×
 * speed) pool, or null when no rated game with a usable rating was played.
 *
 * A game belongs to the session when it was rated, its estimated START
 * lies inside the freeplay authorization window ([freeplayTs],
 * [freeplayTs] + [validityMs]), and it was authorized
 * ([ReadinessGameRecord.freeplayAtPlay], or simply [ReadinessGameRecord.authorized]
 * — within the window the freeplay entry IS the authorizing test, so the
 * flag is a convenience marker, not the source of truth).
 *
 * Per pool: baseline = the latest rating AFTER any earlier rated game in
 * the same pool (pre-session); delta = last in-session rating − baseline.
 * Pools without a pre-session baseline (first ever games there) are
 * skipped — their absolute level says nothing about the session.
 */
fun freeplaySessionNetRatingChange(
    games: List<ReadinessGameRecord>,
    freeplayTs: Long,
    validityMs: Long = ChessReadinessEngine.SESSION_VALIDITY_MS
): Int? {
    class PoolAcc {
        var baseline: Int? = null
        var first: Int? = null
        var last: Int? = null
    }

    val windowEnd = freeplayTs + validityMs
    val pools = LinkedHashMap<String, PoolAcc>()

    fun inSession(g: ReadinessGameRecord): Boolean {
        val startMs = g.endTimeMs - (g.minutes * 60_000).toLong()
        return g.rated && startMs >= freeplayTs && startMs <= windowEnd &&
            (g.freeplayAtPlay || g.authorized)
    }

    for (g in games.sortedBy { it.endTimeMs }) {
        if (!g.rated) continue
        val key = "${g.variant}|${g.type}"
        val pool = pools.getOrPut(key) { PoolAcc() }
        when {
            inSession(g) -> {
                if (pool.first == null) pool.first = g.ratingAfter
                if (g.ratingAfter != null) pool.last = g.ratingAfter
            }
            g.endTimeMs - (g.minutes * 60_000).toLong() < freeplayTs &&
                g.ratingAfter != null -> pool.baseline = g.ratingAfter
            // Anything else (later pools/games) is irrelevant.
        }
    }

    var net = 0
    var any = false
    for (p in pools.values) {
        val base = p.baseline ?: continue // no baseline → skip pool
        val first = p.first ?: continue   // no in-session game → skip pool
        val last = p.last ?: first
        net += last - base
        any = true
    }
    return if (any) net else null
}

/** End time of the session's last in-game (null when no games played). */
fun freeplaySessionLastGameEnd(
    games: List<ReadinessGameRecord>,
    freeplayTs: Long,
    validityMs: Long = ChessReadinessEngine.SESSION_VALIDITY_MS
): Long? {
    val windowEnd = freeplayTs + validityMs
    return games.filter { g ->
        val startMs = g.endTimeMs - (g.minutes * 60_000).toLong()
        g.rated && startMs >= freeplayTs && startMs <= windowEnd &&
            (g.freeplayAtPlay || g.authorized)
    }.maxOfOrNull { it.endTimeMs }
}

/** The settlement decision for one session: refund when net ≥ threshold. */
fun freeplaySessionRefundDue(netRatingChange: Int?): Boolean =
    netRatingChange != null && netRatingChange >= FREEPLAY_REFUND_NET_RATING

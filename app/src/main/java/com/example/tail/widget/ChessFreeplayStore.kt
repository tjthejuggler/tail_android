package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.tail.data.chess.ChessReadinessEngine
import com.example.tail.data.chess.freeplaySessionLastGameEnd
import com.example.tail.data.chess.freeplaySessionNetRatingChange
import com.example.tail.data.chess.freeplaySessionRefundDue
import com.example.tail.data.chess.freeplaySessionSettleAt
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Freeplay — freeplay ticket ledger (fully derived state)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  Once a week the user earns ONE freeplay ticket — granted ONLY while
 *  the current balance (tickets of ANY origin) is below [MAX_STOCK]
 *  (user rule 2026-09-24); weeks arriving at/above the cap are recorded
 *  as skipped and never re-granted. Puzzle Rush all-time records bank
 *  UNCAPPED bonus tickets ([grantBonusTicket]). Spending one from the
 *  bubble widget unlocks rated play
 *  exactly as if a pre-game readiness test had been passed — the grant
 *  itself lives in [ChessReadinessStore.grantFreeplay] as a flagged
 *  GREEN_LIGHT entry in the shared test history. THIS store owns only the
 *  economy, and EVERY quantity is DERIVED from append-only facts:
 *
 *   - GRANTS: the week-index journal [KEY_GRANT_WEEKS] — one immutable
 *     entry per week a credit was granted. Immune to clobbered timestamps
 *     (the earlier mutable-counter design silently minted phantom credits
 *     when `last_accrual_ms` was reset by a concurrent write).
 *   - SPENDS: the usage ledger [KEY_LEDGER] — one entry per freeplay
 *     used, each later gaining its settlement outcome.
 *   - SETTLEMENT: a credit is spent provisionally at grant time. When the
 *     session is COMPLETE — idle-closed 15 minutes after its last game
 *     ([freeplaySessionSettleAt], mirroring the post-game audit's
 *     idle-close semantics) or the window expired — the session's NET
 *     rating change decides: ≥ +1 refunds the credit
 *     ([freeplaySessionNetRatingChange]).
 *
 *   balance = |grant weeks| + |bonus tickets| − unsettled-or-kept
 *           ledger entries (refunded entries drop out entirely)
 *
 *  No stored counters exist anywhere, so no partial write or stale
 *  process can desynchronize the balance.
 *
 *  Storage: plain [SharedPreferences] (synchronous — the bubble service's
 *  menu path must not touch DataStore), same pattern as
 *  [ChessReadinessStore].
 */
object ChessFreeplayStore {

    private const val PREFS_NAME = "tail_chess_freeplay"
    private const val KEY_GRANT_WEEKS = "grant_weeks"

    /**
     * Week indexes PROCESSED but not granted because the balance was
     * already at/above [MAX_STOCK] (user rule 2026-09-24: no weekly
     * ticket while 3+ of any origin are held). Recorded so a skipped
     * week can never be re-granted retroactively.
     */
    private const val KEY_SKIPPED_WEEKS = "skipped_weeks"

    /** Epoch-ms timestamps of UNCAPPED bonus tickets (Puzzle Rush records). */
    private const val KEY_BONUS_GRANTS = "bonus_grants"
    private const val KEY_LEDGER = "ledger"
    private const val KEY_LAST_SETTLE_ATTEMPT = "last_settle_attempt_ms"
    private const val TAG = "ChessFreeplayStore"

    /**
     * Minimum spacing between BACKGROUND settlement attempts. The full
     * settlement parses the whole activity log — expensive enough that it
     * must never run per UI event, only throttled on a worker thread.
     */
    private const val SETTLE_ATTEMPT_MIN_INTERVAL_MS = 60_000L

    /**
     * The largest week gap [accrueLazy] will materialize in one step
     * (520 weeks ≈ a decade). Real gaps never approach this; anything
     * larger is corrupt/foreign data and resets the journal instead of
     * being expanded into an unbounded range.
     */
    private const val MAX_MATERIALIZE_WEEKS = 520L

    /** Single worker for background settlements (serialized, no pile-up). */
    private val settleExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Collapses duplicate background settlements while one is running. */
    private val settleInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Weekly-ticket gate: a WEEKLY ticket is granted only while the
     * current balance — tickets of ANY origin — is below this (user rule
     * 2026-09-24). Tickets from Puzzle Rush records are NOT capped.
     */
    const val MAX_STOCK = 3

    /** Credits granted per elapsed week. */
    const val WEEKLY_GRANT = 1

    /** Net rating gain at/above which a settled session refunds its credit. */
    const val FREEPLAY_REFUND_NET_RATING = 1

    /** One spent freeplay. */
    data class UsageRecord(
        /** Wall-clock instant the freeplay was used. */
        val timestamp: Long,
        /** Timestamp of the GREEN authorization entry it produced. */
        val testTimestamp: Long,
        /** True once the session was settled and the credit refunded. */
        val refunded: Boolean = false,
        /** Net rating change at settlement (null = not settled). */
        val netRatingChange: Int? = null
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads a stored JSON array of longs, or null when absent/corrupt. */
    private fun readLongs(p: SharedPreferences, key: String): List<Long>? = try {
        p.getString(key, null)?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        }
    } catch (_: Exception) {
        null
    }

    // ── Week math (pure, Monday-aligned) ───────────────────────────────────

    /**
     * A monotonically increasing index of ISO weeks (Mondays). Epoch day 0
     * (1970-01-01) is a Thursday, so +3 aligns the division to the Monday
     * boundary: 1970-01-05 (Monday) is the start of week index 1.
     *
     * The `EpochDay` suffix is LOAD-BEARING: when this lived as a
     * `weekIndexOf(Long)` overload beside the epoch-millis variant below,
     * every default-arg call site (`weekIndexOf(nowMs)`) bound to THIS
     * overload (Kotlin prefers the candidate that needs no default
     * arguments) and silently fed MILLISECONDS in as DAYS. The resulting
     * ~2.5e11 week index made [accrueLazy] try to materialize a
     * hundreds-of-billions-element range — an OutOfMemoryError that killed
     * the process on every bubble tap over the chess app (2026-09-22).
     */
    fun weekIndexOfEpochDay(epochDay: Long): Long =
        java.lang.Math.floorDiv(epochDay + 3, 7)

    /** [weekIndexOfEpochDay] for an epoch-millis instant in [zone]. */
    fun weekIndexOf(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        weekIndexOfEpochDay(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay())

    /** Number of ISO-week boundaries crossed between the two indices. */
    fun weeksElapsed(fromWeekIndex: Long, toWeekIndex: Long): Long =
        (toWeekIndex - fromWeekIndex).coerceAtLeast(0)

    /**
     * Pure weekly-grant gate (user rule 2026-09-24): a weekly ticket
     * fits only while the CURRENT balance — tickets of any origin,
     * provisionally-spent ones already deducted — is below [MAX_STOCK].
     * Origin-blind by design: bonus tickets count toward the cap exactly
     * like weekly ones.
     */
    fun weeklyGrantFits(currentBalance: Int): Boolean =
        currentBalance < MAX_STOCK

    /**
     * Pure accrual sweep over [missingWeeks] elapsed weeks: grants the
     * earliest weeks until the balance reaches [MAX_STOCK], skips the
     * rest. Returns (granted, skipped) counts. [grantedCount] and
     * [bonusCount] are the journals' current sizes; [outstanding] the
     * un-refunded spends. Skipped weeks are recorded by [accrueLazy] so
     * they can never be re-granted retroactively.
     */
    fun weeklySweep(
        grantedCount: Int,
        bonusCount: Int,
        outstanding: Int,
        missingWeeks: Int
    ): Pair<Int, Int> {
        var granted = 0
        var skipped = 0
        repeat(missingWeeks.coerceAtLeast(0)) {
            if (weeklyGrantFits(grantedCount + granted + bonusCount - outstanding))
                granted++
            else skipped++
        }
        return granted to skipped
    }

    // ── Ledger ─────────────────────────────────────────────────────────────

    fun usageLedger(context: Context): List<UsageRecord> {
        val raw = prefs(context).getString(KEY_LEDGER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                UsageRecord(
                    timestamp = o.getLong("timestamp"),
                    testTimestamp = o.getLong("testTimestamp"),
                    refunded = o.optBoolean("refunded", false),
                    netRatingChange =
                        if (o.has("netRatingChange") && !o.isNull("netRatingChange"))
                            o.getInt("netRatingChange") else null
                )
            }
        } catch (e: Exception) {
            // A corrupt ledger string must not take down the menu path.
            Log.w(TAG, "ledger unreadable — treating as empty", e)
            emptyList()
        }
    }

    private fun writeLedger(context: Context, ledger: List<UsageRecord>) {
        val arr = JSONArray()
        ledger.forEach {
            arr.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("testTimestamp", it.testTimestamp)
                if (it.refunded) put("refunded", true)
                it.netRatingChange?.let { v -> put("netRatingChange", v) }
            })
        }
        prefs(context).edit().putString(KEY_LEDGER, arr.toString()).apply()
    }

    // ── Grants journal (append-only week indexes) ──────────────────────────

    /** Distinct ISO week indexes ever granted (ascending order). */
    fun grantWeeks(context: Context): List<Long> {
        accrueLazy(context)
        val raw = prefs(context).getString(KEY_GRANT_WEEKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }.distinct().sorted()
        } catch (e: Exception) {
            Log.w(TAG, "grant journal unreadable — treating as empty", e)
            emptyList()
        }
    }

    /**
     * Lazy weekly accrual: sweeps every ISO week index missing from the
     * journals, OLDEST first. Each missing week is processed exactly once
     * — GRANTED (appended to [KEY_GRANT_WEEKS]) while the current balance
     * — tickets of ANY origin, provisionally-spent ones already deducted —
     * is below [MAX_STOCK], otherwise SKIPPED (appended to
     * [KEY_SKIPPED_WEEKS]) so it can never be re-granted retroactively
     * (user rule 2026-09-24). The current week never double-processes
     * because indexes are absolute and recorded in both journals.
     */
    private fun accrueLazy(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val existing = readLongs(p, KEY_GRANT_WEEKS)?.distinct()?.sorted()
        if (existing == null) {
            // First touch: grant the current week's credit only.
            p.edit()
                .putString(KEY_GRANT_WEEKS, JSONArray().put(weekIndexOf(nowMs)).toString())
                .apply()
            return
        }
        val current = weekIndexOf(nowMs)
        // Sweep horizon: the newest week ever processed — granted OR
        // skipped — so skipped weeks are not re-examined.
        val lastProcessed = maxOf(
            existing.lastOrNull() ?: current,
            readLongs(p, KEY_SKIPPED_WEEKS)?.maxOrNull() ?: current
        )
        if (current <= lastProcessed) return
        // SANITY VALVE: the journals must only ever hold sane week
        // indexes. A negative gap (clock rolled back) or a gap longer
        // than a decade means corrupt data — reset to the current week
        // rather than materializing an astronomically long range (an
        // overload mix-up here OOM'd the bubble menu on every tap —
        // 2026-09-22).
        val gap = current - lastProcessed
        if (gap < 0 || gap > MAX_MATERIALIZE_WEEKS) {
            p.edit()
                .putString(KEY_GRANT_WEEKS, JSONArray().put(current).toString())
                .putString(KEY_SKIPPED_WEEKS, JSONArray().toString())
                .apply()
            return
        }
        val newWeeks = (lastProcessed + 1..current).toList()
        val outstanding = usageLedger(context).count { !it.refunded }
        val (grantedN, skippedN) = weeklySweep(
            grantedCount = existing.size,
            bonusCount = readLongs(p, KEY_BONUS_GRANTS)?.size ?: 0,
            outstanding = outstanding,
            missingWeeks = newWeeks.size
        )
        val finalWeeks = (existing + newWeeks.take(grantedN)).distinct().sorted()
        val finalSkipped = ((readLongs(p, KEY_SKIPPED_WEEKS) ?: emptyList()) +
            newWeeks.takeLast(skippedN)).distinct().sorted()
        p.edit()
            .putString(KEY_GRANT_WEEKS, JSONArray(finalWeeks).toString())
            .putString(KEY_SKIPPED_WEEKS, JSONArray(finalSkipped).toString())
            .apply()
    }

    // ── Bonus tickets (UNCAPPED — Puzzle Rush records) ─────────────────────

    /** Epoch-ms timestamps of all banked bonus tickets (ascending). */
    fun bonusTickets(context: Context): List<Long> =
        readLongs(prefs(context), KEY_BONUS_GRANTS) ?: emptyList()

    /**
     * Banks ONE bonus ticket from a new all-time Puzzle Rush record
     * (user rule 2026-09-24: records reward a TICKET, not a green
     * session). Bonus tickets have NO cap — [MAX_STOCK] gates only the
     * WEEKLY grant — and are indistinguishable from weekly tickets at
     * spend/settlement time (origin-blind ledger).
     *
     * @return the ticket's epoch-ms stamp (its identity in the journal).
     */
    fun grantBonusTicket(context: Context): Long {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        val updated = ((readLongs(p, KEY_BONUS_GRANTS) ?: emptyList()) + now).sorted()
        p.edit().putString(KEY_BONUS_GRANTS, JSONArray(updated).toString()).apply()
        return now
    }

    // ── Derived balance ────────────────────────────────────────────────────

    /**
     * The available ticket balance, DERIVED entirely from append-only
     * facts: granted weeks PLUS uncapped bonus tickets minus ledger
     * entries not refunded by settlement, never negative. No stored
     * counter exists — a partial write cannot desynchronize it. The
     * balance is uncapped upward on purpose: only the WEEKLY grant is
     * gated by [MAX_STOCK].
     */
    fun available(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        accrueNow: Boolean = true
    ): Int {
        if (accrueNow) accrueLazy(context, nowMs)
        val p = prefs(context)
        val granted = readLongs(p, KEY_GRANT_WEEKS)?.distinct()?.size ?: 0
        val bonus = readLongs(p, KEY_BONUS_GRANTS)?.size ?: 0
        val spent = usageLedger(context).count { !it.refunded }
        return (granted + bonus - spent).coerceAtLeast(0)
    }

    /**
     * Spends one credit (refusing to go negative) and records it in the
     * ledger. Returns false (nothing consumed) when the balance is empty.
     * Called ONLY by [ChessReadinessStore.grantFreeplay]. The spend is
     * PROVISIONAL — [settleExpired] settles the outcome once the session
     * completes.
     */
    fun consume(context: Context, testTimestamp: Long = System.currentTimeMillis()): Boolean {
        if (available(context) <= 0) return false
        val ledger = usageLedger(context)
        writeLedger(
            context,
            ledger + UsageRecord(
                timestamp = System.currentTimeMillis(),
                testTimestamp = testTimestamp
            )
        )
        return true
    }

    /**
     * Settles every provisionally-spent freeplay whose session is
     * COMPLETE: either the 60-minute window expired, or the session
     * idle-closed — no game for [com.example.tail.data.chess.FREEPLAY_SETTLE_IDLE_MS]
     * after its last game (the same close semantics as the post-game
     * audit), so a net-positive session refunds minutes after the user
     * stops playing instead of an hour later.
     *
     * For each session: the net rating change across its rated games is
     * computed from the canonical activity log
     * ([ChessReadinessLogStore.loadGames]); a net gain of at least
     * [FREEPLAY_REFUND_NET_RATING] refunds the credit. Idempotent — only
     * UNSETTLED ready entries are touched.
     *
     * Safe to call from any UI/poll path — all failures are swallowed.
     *
     * @return the number of entries settled (refunded or kept), for tests.
     */
    fun settleExpired(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        return try {
            val ledger = usageLedger(context)
            val validity = ChessReadinessEngine.SESSION_VALIDITY_MS
            val games = ChessReadinessLogStore.loadGames(context)
            var settled = 0
            val updated = ledger.map { entry ->
                if (entry.netRatingChange != null) return@map entry
                // Settle as soon as the session is COMPLETE: idle-closed
                // 15 min after its last game, or the window expired —
                // whichever comes first.
                val lastGame = freeplaySessionLastGameEnd(games, entry.testTimestamp, validity)
                val settleAt = freeplaySessionSettleAt(entry.testTimestamp, lastGame, validity)
                if (settleAt > nowMs) return@map entry
                val net = freeplaySessionNetRatingChange(games, entry.testTimestamp, validity)
                val refund = freeplaySessionRefundDue(net)
                settled++
                entry.copy(refunded = refund, netRatingChange = net)
            }
            if (settled > 0) writeLedger(context, updated)
            settled
        } catch (e: Exception) {
            Log.w(TAG, "freeplay settlement failed", e)
            0
        }
    }

    /**
     * Cheap, MAIN-THREAD-SAFE gate: true when any unsettled entry could be
     * ready (15 minutes after its grant — the earliest an idle-close can
     * occur). Reads only the tiny ledger — never the activity log.
     */
    /**
     * Number of provisionally-spent credits whose session has not settled
     * yet (no netRatingChange recorded). Shown in the bubble menus so a
     * provisional spend is never mistaken for a permanent one — the
     * 2026-09-24 report ("it still charged me") was exactly that: the
     * session HAD refunded (+3 standard blitz) but nothing displayed it
     * until the next menu render.
     */
    fun pendingSettlementCount(context: Context): Int = try {
        usageLedger(context).count { it.netRatingChange == null }
    } catch (_: Exception) {
        0
    }

    fun hasPendingSettlement(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = try {
        val idle = com.example.tail.data.chess.FREEPLAY_SETTLE_IDLE_MS
        usageLedger(context).any {
            it.netRatingChange == null && it.timestamp + idle <= nowMs
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Fire-and-forget settlement for UI paths ([FloatingBubbleService]
     * menus): checks the cheap gate, throttles to one attempt per
     * [SETTLE_ATTEMPT_MIN_INTERVAL_MS], then runs the full settlement
     * (which parses the whole activity log) on a background worker.
     * Never touches the main thread with heavy work — the earlier
     * synchronous call from the menu OOM'd the process.
     */
    fun settleExpiredAsync(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ) {
        try {
            val p = prefs(context)
            if (nowMs - p.getLong(KEY_LAST_SETTLE_ATTEMPT, 0L) <
                SETTLE_ATTEMPT_MIN_INTERVAL_MS
            ) return
            if (!hasPendingSettlement(context, nowMs)) return
            p.edit().putLong(KEY_LAST_SETTLE_ATTEMPT, nowMs).apply()
            if (!settleInFlight.compareAndSet(false, true)) return
            val app = context.applicationContext
            settleExecutor.execute {
                try {
                    settleExpired(app)
                } catch (_: Throwable) {
                    // Settlement must never crash any caller.
                } finally {
                    settleInFlight.set(false)
                }
            }
        } catch (_: Throwable) {
            // Best-effort by contract.
        }
    }

    /**
     * Repair-script hook: rewrites the grant journal to a fixed set of
     * week indexes and the ledger to fixed entries. The balance then
     * always derives as |weeks| + |bonus| − outstanding entries. The
     * bonus/skipped journals are reset too (parameters let a repair
     * preserve bonus tickets explicitly).
     */
    fun seedForRepair(
        context: Context,
        grantedWeekIndexes: List<Long>,
        ledger: List<UsageRecord>,
        bonusTicketStamps: List<Long> = emptyList(),
        skippedWeekIndexes: List<Long> = emptyList()
    ) {
        prefs(context).edit().apply {
            putString(KEY_GRANT_WEEKS, JSONArray(grantedWeekIndexes).toString())
            putString(KEY_SKIPPED_WEEKS, JSONArray(skippedWeekIndexes).toString())
            putString(KEY_BONUS_GRANTS, JSONArray(bonusTicketStamps).toString())
            putString(KEY_LEDGER, JSONArray().apply {
                ledger.forEach {
                    put(JSONObject().apply {
                        put("timestamp", it.timestamp)
                        put("testTimestamp", it.testTimestamp)
                        if (it.refunded) put("refunded", true)
                        it.netRatingChange?.let { v -> put("netRatingChange", v) }
                    })
                }
            }.toString())
        }.apply()
    }
}

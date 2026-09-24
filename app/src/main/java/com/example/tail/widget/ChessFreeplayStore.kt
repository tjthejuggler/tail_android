package com.example.tail.widget

import android.content.Context
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
 *  Chess Freeplay — weekly freeplay credit ledger (fully derived state)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  Once a week the user earns ONE freeplay credit (stock capped at
 *  [MAX_STOCK]). Spending one from the bubble widget unlocks rated play
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
 *   balance = |grant weeks| − unsettled-or-kept ledger entries
 *           (refunded entries drop out entirely)
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

    /** Maximum credits that can be banked at once. */
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
     * Pure accrual: the number of granted credits after [weeksElapsed]
     * whole weeks, at [WEEKLY_GRANT] per week (the stock cap is applied
     * where the balance is derived, not here).
     */
    fun accrue(grantedWeeks: Int, weeksElapsed: Long): Int =
        grantedWeeks + (weeksElapsed * WEEKLY_GRANT).toInt()

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
     * Lazy weekly accrual: appends the current ISO week index to the
     * grants journal whenever it is missing — the CURRENT week never
     * double-credits because the same index is only appended once, and
     * past weeks cannot be re-granted because indexes are absolute.
     * Missing intermediate weeks (device off for weeks) are materialized
     * too, so the count matches elapsed weeks exactly.
     */
    private fun accrueLazy(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val stored = try {
            p.getString(KEY_GRANT_WEEKS, null)?.let { raw ->
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getLong(it) }
            }
        } catch (_: Exception) {
            null
        }
        val existing = stored?.distinct()?.sorted()
        if (existing == null) {
            // First touch: grant the current week's credit only.
            p.edit()
                .putString(KEY_GRANT_WEEKS, JSONArray().put(weekIndexOf(nowMs)).toString())
                .apply()
            return
        }
        val current = weekIndexOf(nowMs)
        val lastGranted = existing.lastOrNull() ?: current
        if (current <= lastGranted) return
        // SANITY VALVE: the journal must only ever hold sane week indexes.
        // A negative gap (clock rolled back) or a gap longer than a decade
        // means corrupt data — reset to the current week rather than
        // materializing an astronomically long range (an overload mix-up
        // here OOM'd the bubble menu on every tap — 2026-09-22).
        val gap = current - lastGranted
        if (gap < 0 || gap > MAX_MATERIALIZE_WEEKS) {
            p.edit().putString(KEY_GRANT_WEEKS, JSONArray().put(current).toString()).apply()
            return
        }
        // Materialize the missing weeks, NEWEST first — the stock cap
        // lives in the DERIVED balance, so only as many missed weeks are
        // granted as fit under the cap given the outstanding entries.
        val newWeeks = (lastGranted + 1..current).toList()
        val ledger = usageLedger(context)
        val outstanding = ledger.count { !it.refunded }
        val room = (MAX_STOCK + outstanding - existing.size).coerceAtLeast(0)
        val finalWeeks = (existing + newWeeks.takeLast(room)).distinct().sorted()
        p.edit().putString(KEY_GRANT_WEEKS, JSONArray(finalWeeks).toString()).apply()
    }

    // ── Derived balance ────────────────────────────────────────────────────

    /**
     * The available credit balance, DERIVED entirely from append-only
     * facts: granted weeks minus ledger entries not refunded by
     * settlement, clamped to [0, MAX_STOCK]. No stored counter exists —
     * a partial write cannot desynchronize it.
     */
    fun available(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        accrueNow: Boolean = true
    ): Int {
        if (accrueNow) accrueLazy(context, nowMs)
        val granted = grantWeeks(context).size
        val spent = usageLedger(context).count { !it.refunded }
        return (granted - spent).coerceIn(0, MAX_STOCK)
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
     * always derives as |weeks| − outstanding entries.
     */
    fun seedForRepair(
        context: Context,
        grantedWeekIndexes: List<Long>,
        ledger: List<UsageRecord>,
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        prefs(context).edit().apply {
            putString(KEY_GRANT_WEEKS, JSONArray(grantedWeekIndexes).toString())
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

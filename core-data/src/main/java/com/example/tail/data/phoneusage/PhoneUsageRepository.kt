package com.example.tail.data.phoneusage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "PhoneUsageRepo"
private const val PREFS_NAME = "tail_phone_usage_prefs"
private const val KEY_PREFIX_SNAP = "snap:"

/** Events older than this are outside the "day" (04:00 cutoff → next-morning events belong to the previous day). */
private val DAY_END_HOUR = 4
/** Screen-on within 60 s of the previous screen-off is a notification glance — same session. */
private val GLANCE_MERGE_MS = 60_000L
/** App-switch gap tolerance when stitching per-app foreground events into screen sessions. */
private val SESSION_GAP_MS = 15_000L
/** A "long" session threshold. */
private val LONG_SESSION_MS = 20 * 60_000L

/**
 * Storage + aggregation for the phone-usage habit family.
 *
 * ## Storage
 * Snapshots live in ONE SharedPreferences file ([PREFS_NAME]), one small
 * per-day key ("snap:<YYYY-MM-DD>" → snapshot JSON), included in backups via
 * the generic extraPrefs mechanism. All raw usage data stays on-device; only
 * the aggregated daily snapshot is stored.
 *
 * ## Data source
 * UsageStatsManager (special "Usage access" permission):
 *  · queryEvents → ACTIVITY_RESUMED / ACTIVITY_PAUSED transitions, stitched
 *    into screen sessions (app switches and <60 s glances merge).
 *  · queryUsageStats (INTERVAL_DAILY) → per-app foreground totals, used as a
 *    fallback for total minutes and for the category breakdown.
 *
 * UsageStats retains only limited event history (~7 days), so aggregation is
 * driven by a frequent worker; older days that were never captured are marked
 * retro=1 when backfilled from whatever history remains.
 */
class PhoneUsageRepository(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    // ── Permission ───────────────────────────────────────────────────────────

    /** True when the special "Usage access" (PACKAGE_USAGE_STATS) permission is granted. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    fun getSnapshot(date: LocalDate): PhoneUsageSnapshot? =
        prefs.getString(KEY_PREFIX_SNAP + date, null)?.let { PhoneUsageSnapshot.fromJson(it) }

    fun getAllSnapshots(): Map<String, PhoneUsageSnapshot> {
        val out = mutableMapOf<String, PhoneUsageSnapshot>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_SNAP) && value is String) {
                PhoneUsageSnapshot.fromJson(value)?.let { out[it.date] = it }
            }
        }
        return out
    }

    /**
     * THE resume rule (shared by in-app refresh and the worker): a PAST day is
     * done once a snapshot with data exists for it; today is always re-capturable
     * because the day is still accumulating. A stored snapshot with no data at
     * all means the permission was missing — retry it.
     */
    fun needsCapture(date: LocalDate): Boolean {
        if (date == LocalDate.now()) return true
        val snap = getSnapshot(date) ?: return true
        return !snap.hasAnyData
    }

    fun clearAllSnapshots() {
        val e = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_PREFIX_SNAP)) e.remove(key)
        }
        e.apply()
        Log.i(TAG, "clearAllSnapshots: all snapshots removed")
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    /**
     * Aggregates one calendar day into a [PhoneUsageSnapshot]. The day runs
     * from local midnight to 04:00 the NEXT morning (late-night usage counts
     * toward the evening it started). Returns null when usage access is
     * missing or no events at all exist for the day.
     *
     * @param retro marks a retro-capture from the limited (~7 d) UsageStats
     *        history, so the analytics pipeline can tag the recording regime.
     */
    fun captureForDate(date: LocalDate, retro: Boolean = false): PhoneUsageSnapshot? {
        if (!hasUsageAccess()) {
            Log.w(TAG, "capture $date: usage access not granted")
            return null
        }
        val usm = usageStatsManager ?: return null
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone)
        val dayEnd = date.plusDays(1).atTime(DAY_END_HOUR, 0).atZone(zone)
        val startMs = dayStart.toInstant().toEpochMilli()
        val endMs = dayEnd.toInstant().toEpochMilli()

        // ── 1. Event stream → per-app foreground segments ─────────────────────
        data class Seg(var start: Long, var end: Long)
        val segments = mutableListOf<Seg>()
        var openSince = -1L
        val events = usm.queryEvents(startMs, endMs)
        val ev = UsageEvents.Event()
        var firstResumeTs = -1L
        var firstResumePkg = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (openSince < 0) {
                        openSince = ev.timeStamp
                        if (firstResumeTs < 0) {
                            firstResumeTs = ev.timeStamp
                            firstResumePkg = ev.packageName
                        }
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (openSince in 1..(ev.timeStamp - 1)) {
                        segments.add(Seg(openSince, ev.timeStamp))
                    }
                    openSince = -1
                }
            }
        }
        if (openSince > 0) segments.add(Seg(openSince, minOf(System.currentTimeMillis(), endMs)))

        if (segments.isEmpty()) {
            // No events at all → try the per-app interval fallback for totals.
            val fallback = captureTotalsFromIntervals(date, usm, retro)
            if (fallback != null) storeSnapshot(fallback)
            return fallback
        }
        segments.sortBy { it.start }

        // ── 2. Stitch segments into screen sessions ───────────────────────────
        // Gaps < SESSION_GAP (app switches) merge; gaps < GLANCE_MERGE from the
        // user's perspective also merge (notification glances). Merge rule:
        // next.start - cur.end < GLANCE_MERGE_MS → same session (spec's 60 s rule).
        data class Session(var start: Long, var end: Long)
        val sessions = mutableListOf<Session>()
        for (seg in segments) {
            val last = sessions.lastOrNull()
            if (last != null && seg.start - last.end < GLANCE_MERGE_MS) {
                if (seg.end > last.end) last.end = seg.end
            } else {
                sessions.add(Session(seg.start, seg.end))
            }
        }

        val midnightMs = startMs
        fun minuteOf(ts: Long) = ((ts - midnightMs) / 60_000L).toInt()

        val firstUnlockMinute = minuteOf(sessions.first().start)
        val lastUsageMinute = minuteOf(sessions.last().end)
        val sessionDurationsMs = sessions.map { it.end - it.start }
        val totalScreenMinutes = (sessionDurationsMs.sum() / 60_000L).toInt()
        val unlockCount = sessions.size
        val medianSessionTenths = median(sessionDurationsMs)?.let { (it / 6_000L).toInt() } // ms → min×10
        val longSessions = sessionDurationsMs.count { it >= LONG_SESSION_MS }

        // ── 3. Windowed minutes (pre-sleep fallback 21:00–04:00, night 23:00–05:00)
        fun minutesBetween(startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
            // end may cross midnight (e.g. 21:00–28:00 = 21:00–04:00 next day).
            val from = dayStart.plusMinutes(startMinuteOfDay.toLong()).toInstant().toEpochMilli()
            val to = dayStart.plusMinutes(endMinuteOfDay.toLong()).toInstant().toEpochMilli()
            var ms = 0L
            for (s in sessions) {
                val lo = maxOf(s.start, from); val hi = minOf(s.end, to)
                if (hi > lo) ms += hi - lo
            }
            return (ms / 60_000L).toInt()
        }
        val preSleepMinutes = minutesBetween(21 * 60, 28 * 60) // 21:00–04:00 fallback window
        val nightMinutes = minutesBetween(23 * 60, 29 * 60)    // 23:00–05:00

        // ── 4. Per-app interval totals (categories + first-app label) ─────────
        val intervalStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startMs, endMs
        ).orEmpty()
        val categoryMinutes = HashMap<String, Long>()
        for (stat in intervalStats) {
            val min = stat.totalTimeInForeground / 60_000L
            if (min <= 0) continue
            val cat = appCategoryOf(stat.packageName)
            categoryMinutes[cat] = (categoryMinutes[cat] ?: 0L) + min
        }
        val top3 = categoryMinutes.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
            .joinToString("|")

        val snap = PhoneUsageSnapshot(
            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            firstUnlockMinute = firstUnlockMinute,
            lastUsageMinute = lastUsageMinute,
            totalScreenMinutes = totalScreenMinutes,
            unlockCount = unlockCount,
            medianSessionTenths = medianSessionTenths,
            longSessions = longSessions,
            preSleepMinutes = preSleepMinutes,
            preSleepIsFallback = true,
            nightScreenMinutes = nightMinutes,
            firstApp = appLabelOf(firstResumePkg),
            top3Categories = top3,
            retro = retro,
            fetchedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        storeSnapshot(snap)
        Log.d(TAG, "captured $date: ${sessions.size} session(s), $totalScreenMinutes min total")
        return snap
    }

    /** Totals-only snapshot from the per-app interval query (event query empty). */
    private fun captureTotalsFromIntervals(
        date: LocalDate,
        usm: UsageStatsManager,
        retro: Boolean
    ): PhoneUsageSnapshot? {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone)
        val startMs = dayStart.toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atTime(DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs).orEmpty()
        if (stats.isEmpty()) return null
        var totalMs = 0L
        val categoryMinutes = HashMap<String, Long>()
        for (stat in stats) {
            totalMs += stat.totalTimeInForeground
            val min = stat.totalTimeInForeground / 60_000L
            if (min > 0) {
                val cat = appCategoryOf(stat.packageName)
                categoryMinutes[cat] = (categoryMinutes[cat] ?: 0L) + min
            }
        }
        if (totalMs <= 0) return null
        val top3 = categoryMinutes.entries.sortedByDescending { it.value }.take(3).map { it.key }
            .joinToString("|")
        return PhoneUsageSnapshot(
            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            totalScreenMinutes = (totalMs / 60_000L).toInt(),
            top3Categories = top3,
            retro = retro,
            fetchedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    private fun storeSnapshot(snap: PhoneUsageSnapshot) {
        prefs.edit().putString(KEY_PREFIX_SNAP + snap.date, snap.toJson()).apply()
    }

    /** Human-readable app label for a package (falls back to the package name). */
    private fun appLabelOf(packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * Backfills every day the device still retains UsageStats history for
     * (typically ~7 days) that has no snapshot yet. Backfilled days are
     * flagged retro=1. Never reaches deeper than the OS keeps.
     */
    fun backfillRecent(days: Int = 7): Map<String, PhoneUsageSnapshot> {
        val out = mutableMapOf<String, PhoneUsageSnapshot>()
        val today = LocalDate.now()
        for (i in 1..days) {
            val d = today.minusDays(i.toLong())
            if (!needsCapture(d)) continue
            captureForDate(d, retro = true)?.let { out[it.date] = it }
        }
        if (out.isNotEmpty()) Log.i(TAG, "backfill: +${out.size} retro day(s)")
        return out
    }
}

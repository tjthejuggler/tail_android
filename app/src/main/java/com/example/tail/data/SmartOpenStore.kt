package com.example.tail.data

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SMART OPEN — context-aware initial habit screen
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Learns, per habit screen, which contexts (time of day, recently used
 * app, coarse location) tend to precede increments on that screen, then
 * guesses the best screen the next time the app is opened "plainly"
 * (launcher icon or the widget's "Main" zone). A wrong guess is harmless;
 * a right guess saves a swipe.
 *
 * Learning happens on every habit increment recorded through the app
 * (see [recordFromAppAsync]). Resolution happens on plain launches only
 * (see [resolveForLaunch]); explicit deep links (widget touch zones for a
 * specific screen, notification routes) always win.
 *
 * SIGNALS
 *  · h=<0..11>      2-hour time-of-day bucket (weight 1)
 *  · app=<pkg>      most recent non-tail foreground app within the last
 *                   15 minutes (weight 2) — "I was just juggling"
 *  · loc=<lat:lon>  ~100 m location cell (weight 3) — "I'm at the gym";
 *                   only recorded when location permission is ALREADY
 *                   granted (never requested for this feature)
 *
 * MEMORY HYGIENE
 *  · every write prunes signal entries whose last update is older than
 *    [RETENTION_DAYS] (two weeks), so stale locations/apps/times fade out
 *  · per-signal counts are capped ([MAX_COUNT]) so one busy day can't
 *    dominate forever
 *  · weight decays linearly to zero across the retention window
 *
 * All persistence is one small JSON blob in SharedPreferences — no DB, no
 * loading-time cost (resolution is a prefs read + a cheap usage-events
 * query, run on IO before the grid's screens even finish loading).
 */
object SmartOpenStore {

    private const val TAG = "SmartOpen"
    private const val PREFS = "smart_open"
    private const val KEY_SIGNALS = "signals"

    /** Signals not reinforced for this many days are dropped entirely. */
    const val RETENTION_DAYS = 14L

    /** Per-signal count cap — keeps one context from being unlearnable. */
    const val MAX_COUNT = 20

    /** A guess must clear this absolute score … */
    const val MIN_SCORE = 1.5

    /** … and beat the runner-up by this factor (avoid coin-flip switches). */
    const val DOMINANCE = 1.5

    // ────────────────────────────────────────────────────────────────────
    // Pure logic — unit-testable, no Android dependencies
    // ────────────────────────────────────────────────────────────────────

    /** 2-hour bucket of a 0..23 hour. */
    fun hourBucket(hour: Int): Int = (hour / 2).coerceIn(0, 11)

    /** ~100 m grid cell key (3 decimals of a degree ≈ 100 m). */
    fun locKey(lat: Double, lon: Double): String =
        "loc=%.3f:%.3f".format(lat, lon)

    fun weightOf(key: String): Double = when {
        key.startsWith("loc=") -> 3.0
        key.startsWith("app=") -> 2.0
        else -> 1.0
    }

    /** The signal keys describing a moment in context. */
    fun signalKeys(hour: Int, foregroundPkg: String?, loc: Pair<Double, Double>?): List<String> {
        val keys = mutableListOf("h=${hourBucket(hour)}")
        foregroundPkg?.takeIf { it.isNotBlank() }?.let { keys.add("app=$it") }
        loc?.let { keys.add(locKey(it.first, it.second)) }
        return keys
    }

    /** Linear decay: 1.0 when just used → 0.0 at [RETENTION_DAYS]. */
    private fun recency(daysSince: Long): Double =
        max(0.0, 1.0 - daysSince.toDouble() / RETENTION_DAYS)

    /**
     * JSON shape: `{ "<screen>": { "<signalKey>": {"c": count, "d": epochDay} } }`
     * Reinforces [keys] for [screenIndex], pruning as we go.
     */
    fun record(json: String, screenIndex: Int, keys: List<String>, nowEpochDay: Long): String {
        val root = pruneObj(if (json.isBlank()) JSONObject() else JSONObject(json), nowEpochDay)
        val screenObj = root.optJSONObject(screenIndex.toString()) ?: JSONObject()
        for (key in keys) {
            val entry = screenObj.optJSONObject(key) ?: JSONObject()
            val count = (entry.optInt("c", 0) + 1).coerceAtMost(MAX_COUNT)
            entry.put("c", count)
            entry.put("d", nowEpochDay)
            screenObj.put(key, entry)
        }
        root.put(screenIndex.toString(), screenObj)
        return root.toString()
    }

    /** Drops entries older than [RETENTION_DAYS] and emptied screens. */
    fun prune(json: String, nowEpochDay: Long): String =
        pruneObj(if (json.isBlank()) JSONObject() else JSONObject(json), nowEpochDay).toString()

    private fun pruneObj(root: JSONObject, nowEpochDay: Long): JSONObject {
        for (screenKey in root.keys().asSequence().toList()) {
            val screenObj = root.optJSONObject(screenKey) ?: continue
            for (signalKey in screenObj.keys().asSequence().toList()) {
                val lastDay = screenObj.optJSONObject(signalKey)?.optLong("d", 0L) ?: 0L
                if (nowEpochDay - lastDay >= RETENTION_DAYS) screenObj.remove(signalKey)
            }
            if (screenObj.length() == 0) root.remove(screenKey)
        }
        return root
    }

    /**
     * Best screen for the given current-context [keys], or -1 when nothing
     * is confidently better than the alternatives.
     */
    fun resolve(json: String, keys: List<String>, nowEpochDay: Long): Int {
        if (json.isBlank() || keys.isEmpty()) return -1
        val root = JSONObject(json)
        var bestScreen = -1
        var bestScore = 0.0
        var secondScore = 0.0
        for (screenKey in root.keys()) {
            val screen = screenKey.toIntOrNull() ?: continue
            val screenObj = root.optJSONObject(screenKey) ?: continue
            var score = 0.0
            for (key in keys) {
                val entry = screenObj.optJSONObject(key) ?: continue
                val daysSince = nowEpochDay - entry.optLong("d", 0L)
                val r = recency(daysSince)
                if (r <= 0) continue
                score += weightOf(key) * entry.optInt("c", 0) * r
            }
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestScreen = screen
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        return if (bestScreen >= 0 &&
            bestScore >= MIN_SCORE &&
            bestScore >= DOMINANCE * secondScore
        ) bestScreen else -1
    }

    // ────────────────────────────────────────────────────────────────────
    // Android glue
    // ────────────────────────────────────────────────────────────────────

    private val ioLock = Any()

    /**
     * Learns "screen [screenIndex] was used in this context". Fire-and-
     * forget: gathers the context signals cheaply and persists on the
     * calling dispatcher (call from IO). Safe to call from any thread.
     */
    fun recordFromApp(context: Context, screenIndex: Int) {
        try {
            val appContext = context.applicationContext
            val now = LocalDateTime.now()
            val keys = signalKeys(
                hour = now.hour,
                foregroundPkg = recentForegroundPkg(appContext),
                loc = lastKnownCell(appContext)
            )
            synchronized(ioLock) {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val updated = record(
                    prefs.getString(KEY_SIGNALS, "").orEmpty(),
                    screenIndex, keys, now.toLocalDate().toEpochDay()
                )
                prefs.edit().putString(KEY_SIGNALS, updated).apply()
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "record failed: ${t.message}")
        }
    }

    /**
     * Best-guess screen for a plain launch, or -1. Cheap (one prefs read,
     * one 15-minute usage-events query, best-effort last-known location);
     * intended to run on IO while the grid loads its screens.
     */
    fun resolveForLaunch(context: Context): Int {
        return try {
            val appContext = context.applicationContext
            val now = LocalDateTime.now()
            val json = appContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SIGNALS, "").orEmpty()
            resolve(
                json,
                signalKeys(now.hour, recentForegroundPkg(appContext), lastKnownCell(appContext)),
                now.toLocalDate().toEpochDay()
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "resolve failed: ${t.message}")
            -1
        }
    }

    /**
     * Most recent non-tail app the user had in the foreground within the
     * last 15 minutes (usage-events stream — same technique as
     * WidgetTriggerService, which needs the Usage Access grant the user
     * already gave for widget triggers). Null when unknown.
     */
    private fun recentForegroundPkg(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events: UsageEvents = usm.queryEvents(now - 15 * 60_000L, now)
            var pkg: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    e.packageName != context.packageName
                ) {
                    pkg = e.packageName
                }
            }
            pkg
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Coarse last-known location cell, ONLY if a location permission is
     * already granted (the app declares COARSE for the map feature; this
     * feature never requests it). Purely passive — no fetch, no GPS start.
     */
    private fun lastKnownCell(context: Context): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            val last = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).firstNotNullOfOrNull { p ->
                try {
                    @Suppress("MissingPermission")
                    lm.getLastKnownLocation(p)
                } catch (_: Throwable) { null }
            } ?: return null
            // Ignore stale fixes older than an hour.
            if (System.currentTimeMillis() - last.time > 60 * 60_000L) return null
            Pair(last.latitude, last.longitude)
        } catch (_: Throwable) {
            null
        }
    }

    /** Local-zone hour of a [LocalDateTime] — exposed for tests. */
    fun hourOf(now: LocalDateTime): Int = now.atZone(ZoneId.systemDefault()).hour
}

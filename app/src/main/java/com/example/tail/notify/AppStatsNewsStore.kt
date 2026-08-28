package com.example.tail.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Persistent "App Stats news" feed — the record notifications' long-lived
 * home inside the App Stats screen.
 *
 * Unlike the dismissable notification center ([com.example.tail.data.NotificationStore]),
 * entries here are NOT dismissable: they simply age out after
 * [RETENTION_DAYS] days. The App Stats screen shows them (newest first)
 * behind a 🏆 top-bar icon so records close to breaking / just broken are
 * visible at a glance.
 *
 * Storage: SharedPreferences + JSON (small list, no DataStore needed).
 */
object AppStatsNewsStore {

    private const val PREFS = "app_stats_record_notify"
    private const val KEY_NEWS = "news_feed"
    private const val KEY_VERSION = "feed_version"

    /**
     * Bumping this wipes the feed once: entries computed by an older (buggy)
     * series builder disappear instead of lingering with wrong numbers.
     * v2: fixes inverted-binary double-counting and no-points streak parity.
     */
    private const val FEED_VERSION = 2

    /** How long news entries stay visible before aging out. */
    const val RETENTION_DAYS = 7L

    data class Entry(
        val id: String,
        val verdict: AppStatsRecordEngine.Verdict,
        val metric: String,
        val title: String,
        val message: String,
        val day: String,            // "yyyy-MM-dd" the event refers to
        val createdAtMillis: Long
    )

    /**
     * One-time migration for [FEED_VERSION] bumps: wipes the feed (and, via
     * the caller, the episode flags) when the stored version is older.
     * @return true when a migration was performed.
     */
    fun migrateIfNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 1) >= FEED_VERSION) return false
        prefs.edit().remove(KEY_NEWS).putInt(KEY_VERSION, FEED_VERSION).apply()
        return true
    }

    /** Current feed, newest first, pruned of expired entries. */
    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_NEWS, null) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val entries = mutableListOf<Entry>()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = runCatching {
                Entry(
                    id = o.getString("id"),
                    verdict = AppStatsRecordEngine.Verdict.valueOf(o.getString("verdict")),
                    metric = o.getString("metric"),
                    title = o.getString("title"),
                    message = o.getString("message"),
                    day = o.getString("day"),
                    createdAtMillis = o.getLong("at")
                )
            }.getOrNull() ?: continue
            if (e.createdAtMillis >= cutoff) entries += e
        }
        return entries.sortedByDescending { it.createdAtMillis }
    }

    /**
     * Adds [entries] (dedup by id — a repeated check the same day never
     * duplicates an entry) and persists the pruned feed.
     */
    fun add(context: Context, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = load(context).associateBy { it.id }.toMutableMap()
        entries.forEach { existing.putIfAbsent(it.id, it) }
        val kept = existing.values.sortedByDescending { it.createdAtMillis }
        prefs.edit().putString(KEY_NEWS, encode(kept)).apply()
    }

    private fun encode(entries: Collection<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("verdict", e.verdict.name)
                    .put("metric", e.metric)
                    .put("title", e.title)
                    .put("message", e.message)
                    .put("day", e.day)
                    .put("at", e.createdAtMillis)
            )
        }
        return arr.toString()
    }
}

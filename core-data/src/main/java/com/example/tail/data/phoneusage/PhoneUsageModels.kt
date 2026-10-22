package com.example.tail.data.phoneusage

import org.json.JSONObject

/**
 * One day's phone-usage snapshot — the "phone usage" habit family.
 *
 * Everything is captured on-device from the Android UsageStatsManager
 * (requires the PACKAGE_USAGE_STATS "Usage access" special permission).
 * Times are minutes from local midnight, stored as integers; null means
 * no data. UsageStats only retains a limited event history (~7 days), so
 * aggregation must run frequently (see PhoneUsageWorker).
 *
 * Mirrors [com.example.tail.data.environment.EnvironmentSnapshot]: all
 * metrics are nullable and a snapshot is stored even when partial.
 */
data class PhoneUsageSnapshot(
    /** ISO date string ("YYYY-MM-DD") this snapshot belongs to. */
    val date: String,
    /** Minutes-since-midnight of the first unlock/screen-on of the day. */
    val firstUnlockMinute: Int? = null,
    /** Minutes-since-midnight of the last usage end before the 04:00 cutoff. */
    val lastUsageMinute: Int? = null,
    /** Total foreground screen minutes for the day. */
    val totalScreenMinutes: Int? = null,
    /** Distinct unlock/session starts (glances within 60 s merged). */
    val unlockCount: Int? = null,
    /** Median session duration, ×10 minutes (125 = 12.5 min). */
    val medianSessionTenths: Int? = null,
    /** Sessions of 20 minutes or longer. */
    val longSessions: Int? = null,
    /** Screen minutes in the 2 h before sleep onset (fallback window 21:00–04:00). */
    val preSleepMinutes: Int? = null,
    /** True when preSleepMinutes used the fallback window (no sleep midpoint). */
    val preSleepIsFallback: Boolean = true,
    /** Screen minutes between 23:00 and 05:00. */
    val nightScreenMinutes: Int? = null,
    /** First app opened after the first unlock (human-readable label). */
    val firstApp: String = "",
    /** Top-3 app-category buckets by minutes, pipe-separated ("dev|browser|video"). */
    val top3Categories: String = "",
    /** True when retro-captured from the ~7-day UsageStats history on first setup. */
    val retro: Boolean = false,
    /** ISO-8601 timestamp of the aggregation run that produced this snapshot. */
    val fetchedAt: String = ""
) {
    /** Serialises to one JSON object (stored in a prefs map keyed by date). */
    fun toJson(): String = JSONObject().apply {
        put("date", date)
        firstUnlockMinute?.let { put("firstUnlock", it) }
        lastUsageMinute?.let { put("lastUsage", it) }
        totalScreenMinutes?.let { put("totalMin", it) }
        unlockCount?.let { put("unlocks", it) }
        medianSessionTenths?.let { put("medianT", it) }
        longSessions?.let { put("longSess", it) }
        preSleepMinutes?.let { put("preSleep", it) }
        if (preSleepMinutes != null) put("preSleepFb", preSleepIsFallback)
        nightScreenMinutes?.let { put("nightMin", it) }
        if (firstApp.isNotEmpty()) put("firstApp", firstApp)
        if (top3Categories.isNotEmpty()) put("top3", top3Categories)
        if (retro) put("retro", true)
        if (fetchedAt.isNotEmpty()) put("fetchedAt", fetchedAt)
    }.toString()

    /** True when at least one metric field is present. */
    val hasAnyData: Boolean
        get() = firstUnlockMinute != null || lastUsageMinute != null ||
            totalScreenMinutes != null || unlockCount != null ||
            medianSessionTenths != null || longSessions != null ||
            preSleepMinutes != null || nightScreenMinutes != null

    companion object {
        /** Parses one snapshot; null when [text] is corrupt. */
        fun fromJson(text: String): PhoneUsageSnapshot? = runCatching {
            val o = JSONObject(text)
            PhoneUsageSnapshot(
                date = o.getString("date"),
                firstUnlockMinute = if (o.has("firstUnlock")) o.getInt("firstUnlock") else null,
                lastUsageMinute = if (o.has("lastUsage")) o.getInt("lastUsage") else null,
                totalScreenMinutes = if (o.has("totalMin")) o.getInt("totalMin") else null,
                unlockCount = if (o.has("unlocks")) o.getInt("unlocks") else null,
                medianSessionTenths = if (o.has("medianT")) o.getInt("medianT") else null,
                longSessions = if (o.has("longSess")) o.getInt("longSess") else null,
                preSleepMinutes = if (o.has("preSleep")) o.getInt("preSleep") else null,
                preSleepIsFallback = o.optBoolean("preSleepFb", true),
                nightScreenMinutes = if (o.has("nightMin")) o.getInt("nightMin") else null,
                firstApp = o.optString("firstApp", ""),
                top3Categories = o.optString("top3", ""),
                retro = o.optBoolean("retro", false),
                fetchedAt = o.optString("fetchedAt", "")
            )
        }.getOrNull()
    }
}

// ── Metric registry ─────────────────────────────────────────────────────────

/**
 * The phone-usage metrics a habit can be linked to (the "phone usage"
 * habit-type family). Following the environment-link model, a linked
 * habit's daily stored value IS the metric value (plain integer — these
 * metrics are naturally integer-valued), so graphs work natively.
 *
 * MEDIAN_SESSION is stored ×10 (125 = 12.5 min) so one decimal survives;
 * pair it with the habit's divider (10) for display, like the
 * environment ×10 temperatures.
 */
enum class PhoneUsageMetric(
    val key: String,
    val label: String,
    val unit: String,
    val extract: (PhoneUsageSnapshot) -> Int?
) {
    FIRST_UNLOCK("first_unlock", "Phone usage: first unlock minute", "min", { it.firstUnlockMinute }),
    LAST_USAGE("last_usage", "Phone usage: evening last usage minute", "min", { it.lastUsageMinute }),
    TOTAL_MINUTES("total_minutes", "Phone usage: total screen minutes", "min", { it.totalScreenMinutes }),
    UNLOCK_COUNT("unlock_count", "Phone usage: unlock count", "", { it.unlockCount }),
    MEDIAN_SESSION("median_session", "Phone usage: median session minutes", "min", { it.medianSessionTenths }),
    LONG_SESSIONS("long_sessions", "Phone usage: long sessions (20min+)", "", { it.longSessions }),
    PRE_SLEEP_MINUTES("pre_sleep_minutes", "Phone usage: pre-sleep screen minutes", "min", { it.preSleepMinutes }),
    NIGHT_MINUTES("night_minutes", "Phone usage: night screen minutes", "min", { it.nightScreenMinutes });

    /** The day's value as stored in the habits DB; null = no data. */
    fun scaledValue(snapshot: PhoneUsageSnapshot): Int? = extract(snapshot)

    companion object {
        fun fromKey(key: String?): PhoneUsageMetric? =
            entries.firstOrNull { it.key == key }
    }
}

// ── App-category bucketing ──────────────────────────────────────────────────

/**
 * Maps a package name to a coarse usage bucket using default heuristics.
 * Kept purely string-based so it stays testable without Android.
 */
fun appCategoryOf(packageName: String): String {
    val p = packageName.lowercase()
    return when {
        listOf("whatsapp", "telegram", "signal", "messenger", "sms", "messaging", "discord", "slack").any { it in p } -> "messaging"
        listOf("browser", "chrome", "firefox", "firefox", "duckduckgo", "brave", "vanadium").any { it in p } -> "browser"
        listOf("youtube", "netflix", "primevideo", "disney", "video", "twitch", "newpipe", "jellyfin", "vlc").any { it in p } -> "video"
        listOf("read", "book", "moon+", "kindle", "anki", "ko").any { it in p } -> "reading"
        listOf("studio", "termux", "vim", "code", "git", "ssh", "connectbot", "ide", "compiler").any { it in p } -> "dev"
        listOf("game", "chess", "puzzle", "sudoku", "play.").any { it in p } -> "games"
        listOf("instagram", "tiktok", "reddit", "twitter", "x.", "facebook", "threads", "mastodon", "bluesky").any { it in p } -> "social"
        p.contains("launcher") -> "other"
        else -> "other"
    }
}

/**
 * Formats minutes-since-midnight as a clock time ("07:42"). Values past
 * midnight (the day runs to 04:00 next morning) wrap modulo 24 h, so 1490
 * renders as "00:50".
 */
fun formatMinutesAsClock(minutes: Int): String {
    val m = ((minutes % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

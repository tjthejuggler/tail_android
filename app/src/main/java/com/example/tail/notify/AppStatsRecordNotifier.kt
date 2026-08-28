package com.example.tail.notify

import android.content.Context
import android.util.Log
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.applyDivider
import com.example.tail.data.dateString
import com.example.tail.data.effectivePointsWithFallback
import com.example.tail.data.fallbackSlotKey
import com.example.tail.data.invertedBinaryPointsForDate
import com.example.tail.data.isInternalValueKey
import com.example.tail.data.minutesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val TAG = "AppStatsRecords"

/**
 * Android glue for the app-stats record notifications
 * (see [AppStatsRecordEngine] for the pure logic).
 *
 * Loads the habits DB, builds the daily aggregate series (total points,
 * habits done, per-habit streak/anti-streak sums and counts — the same
 * semantics as the App Stats screen), runs the record engine and posts any
 * near-record / record-broken notices through the standard notification
 * system ([HabitAsks.postInfo]).
 *
 * Episode state (one "record broken" notification per record-setting run)
 * persists in SharedPreferences so daily re-breaking of a live record never
 * spams.
 */
object AppStatsRecordNotifier {

    private const val PREFS = "app_stats_record_notify"
    private const val KEY_EPISODES = "episode_notified_metrics"

    /** Max notifications one check may post (broken first, then near). */
    const val MAX_POSTS_PER_CHECK = 3

    /**
     * Runs one full check. Safe to call repeatedly (app open, daily alarm):
     * near-record ids are per-day (NotificationStore dedups) and broken
     * records are gated by the persisted episode flags.
     */
    suspend fun checkAndPost(appContext: Context) = withContext(Dispatchers.IO) {
        try {
            // Feed-version migration: after a series-builder fix, wipe the
            // stale feed AND the episode flags so wrong numbers vanish and
            // records re-evaluate cleanly.
            if (AppStatsNewsStore.migrateIfNeeded(appContext)) {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_EPISODES).apply()
            }
            val settings = SettingsRepository(appContext).settingsFlow.first()
            if (!settings.appStatsRecordNotificationsEnabled) return@withContext
            if (settings.fileUri.isEmpty()) return@withContext
            val db = HabitsRepository()
                .loadDatabase(android.net.Uri.parse(settings.fileUri), appContext)
            if (db.isEmpty()) return@withContext

            val series = buildSeries(db, settings)
            val today = dateString(LocalDate.now())
            val states = loadStates(appContext)
            val result = AppStatsRecordEngine.evaluate(series, today, states)
            saveStates(appContext, result.updatedStates)

            // Every evaluation goes into the persistent App Stats news feed
            // (visible in the App Stats screen, ages out after a week)…
            val now = System.currentTimeMillis()
            AppStatsNewsStore.add(
                appContext,
                result.evaluations.map { ev ->
                    AppStatsNewsStore.Entry(
                        id = "appstats:${ev.verdict.name.lowercase()}:${ev.metric}:$today",
                        verdict = ev.verdict,
                        metric = ev.metric,
                        title = ev.title,
                        message = ev.message,
                        day = today,
                        createdAtMillis = now
                    )
                }
            )
            // …but only the top few become system notifications, and at most
            // ONE per metric per DAY: once a category notified today it stays
            // quiet for the rest of the day — EXCEPT when a genuinely NEW
            // record is set (BROKEN with a higher record value than any
            // already notified today), which always gets through.
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val nowMs = System.currentTimeMillis()
            val todayKey = today
            var posted = 0
            for (ev in result.evaluations) {
                if (posted >= MAX_POSTS_PER_CHECK) break
                val lastRaw = prefs.getString("last_sent_${ev.metric}", null)
                // NOTE: the marker is written space-separated ("VERDICT value millis day");
                // it was previously parsed with a \u0001 delimiter that never
                // matched, so the once-per-day-per-metric gate never suppressed
                // anything and NEAR notices re-posted on every check.
                val lastParts = lastRaw?.split(" ")
                val lastDay = lastParts?.getOrNull(3)
                val lastValue = lastParts?.getOrNull(1)?.toIntOrNull() ?: Int.MIN_VALUE
                val alreadyNotifiedToday = lastDay == todayKey
                val isNewRecord = ev.verdict == AppStatsRecordEngine.Verdict.BROKEN &&
                    ev.recordValue > lastValue
                if (alreadyNotifiedToday && !isNewRecord) continue
                val prefix = if (ev.verdict == AppStatsRecordEngine.Verdict.BROKEN) "rec" else "near"
                HabitAsks.postInfo(
                    appContext = appContext,
                    id = "appstats:$prefix:${ev.metric}:$today",
                    title = ev.title,
                    message = ev.message,
                    habitLabel = "App Stats"
                )
                prefs.edit().putString(
                    "last_sent_${ev.metric}",
                    "${ev.verdict.name} ${ev.recordValue} $nowMs $todayKey"
                ).apply()
                posted++
            }
            if (posted > 0) {
                Log.i(TAG, "Posted $posted app-stats record notification(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "App-stats record check failed: ${e.message}")
        }
    }

    // ── Series construction (App Stats screen semantics) ───────────────────

    /**
     * Builds the daily aggregate series. Mirrors computeAppStats: effective
     * points per habit per day (dividers, fallback slots, minutes-primary,
     * inverted-binary), habit counts on points > 0, and per-habit streak /
     * anti-streak aggregates over enabled, point-earning habits.
     */
    internal fun buildSeries(db: HabitsDatabase, settings: AppSettings): AppStatsRecordEngine.Series {
        val today = LocalDate.now()
        val todayStr = dateString(today)

        val allDates = mutableSetOf<String>()
        db.values.forEach { entries -> allDates.addAll(entries.keys) }
        allDates.add(todayStr)
        val sortedDates = allDates.sorted()
        if (sortedDates.isEmpty()) return emptySeries()

        fun effPts(habitName: String, raw: Int, dateStr: String): Int {
            if (habitName in settings.invertedBinaryHabits) {
                return invertedBinaryPointsForDate(db[habitName] ?: emptyMap(), dateStr)
            }
            val div = settings.habitDividers[habitName] ?: 1
            if (habitName in settings.widgetTimerMinutesPrimary) {
                val minutes = db[minutesKey(habitName)]?.get(dateStr) ?: 0
                return effectivePointsWithFallback(minutes, div, raw, true)
            }
            if (habitName !in settings.secondaryValueFallbackHabits) return applyDivider(raw, div)
            val fallbackVal = db[fallbackSlotKey(habitName, settings.secondaryValueHabits, db)]
                ?.get(dateStr) ?: 0
            return effectivePointsWithFallback(raw, div, fallbackVal, true)
        }

        val pointHabits = db.keys.filter {
            it !in settings.noPointsHabits && !isInternalValueKey(it)
        }

        // Daily totals + habit counts
        val totals = IntArray(sortedDates.size)
        val counts = IntArray(sortedDates.size)
        val dateIdx = sortedDates.withIndex().associate { (i, d) -> d to i }
        for (habitName in pointHabits) {
            val entries = db[habitName] ?: emptyMap()
            // Inverted-binary habits are handled exclusively in the dedicated
            // pass below — counting their explicit entries here too would
            // double-count days logged with a raw 0.
            if (habitName in settings.invertedBinaryHabits) {
                val firstData = entries.filterValues { it != 0 }.keys.minOrNull()
                    ?: sortedDates.first()
                for ((idx, dateStr) in sortedDates.withIndex()) {
                    if (dateStr < firstData) continue
                    val pts = effPts(habitName, entries[dateStr] ?: 0, dateStr)
                    if (pts > 0) {
                        totals[idx] += pts
                        counts[idx]++
                    }
                }
                continue
            }
            for ((dateStr, raw) in entries) {
                val idx = dateIdx[dateStr] ?: continue
                val pts = effPts(habitName, raw, dateStr)
                totals[idx] += pts
                if (pts > 0) counts[idx]++
            }
        }

        // Per-habit streak / anti-streak per date (enabled habits only, from
        // each habit's first entry date — same as the App Stats graphs).
        // NOTE: like the App Stats screen's streak aggregates, this INCLUDES
        // no-points habits (they can still carry streaks) — only internal
        // value-slot keys and disabled habits are skipped.
        val streakSums = IntArray(sortedDates.size)
        val antiStreakSums = IntArray(sortedDates.size)
        val streakCounts = IntArray(sortedDates.size)
        val antiStreakCounts = IntArray(sortedDates.size)
        val parsedDates = sortedDates.map { runCatching { LocalDate.parse(it) }.getOrNull() }

        for (habitName in db.keys.filter { !isInternalValueKey(it) }) {
            if (habitName in settings.disabledHabits) continue
            val entries = db[habitName] ?: emptyMap()
            val habitFirstDate = entries.keys.minOrNull() ?: continue
            var lastDoneDate: LocalDate? = null
            var streak = 0
            var habitStarted = false
            for ((idx, dateStr) in sortedDates.withIndex()) {
                if (!habitStarted) {
                    if (dateStr >= habitFirstDate) habitStarted = true else continue
                }
                val currDate = parsedDates[idx] ?: continue
                val pts = effPts(habitName, entries[dateStr] ?: 0, dateStr)
                if (pts > 0) {
                    streak = if (lastDoneDate != null &&
                        ChronoUnit.DAYS.between(lastDoneDate, currDate) == 1L
                    ) streak + 1 else 1
                    lastDoneDate = currDate
                    streakSums[idx] += streak
                    streakCounts[idx]++
                } else {
                    streak = 0
                    val antiStrk = if (lastDoneDate != null) {
                        ChronoUnit.DAYS.between(lastDoneDate, currDate).toInt()
                    } else {
                        val firstDate = parsedDates.getOrNull(
                            sortedDates.indexOfFirst { it >= habitFirstDate }
                        )
                        if (firstDate != null) ChronoUnit.DAYS.between(firstDate, currDate).toInt() else 0
                    }
                    if (antiStrk > 0) {
                        antiStreakSums[idx] += antiStrk
                        antiStreakCounts[idx]++
                    }
                }
            }
        }

        return AppStatsRecordEngine.Series(
            dates = sortedDates,
            dailyTotals = totals.toList(),
            dailyHabitCounts = counts.toList(),
            dailyStreakSums = streakSums.toList(),
            dailyAntiStreakSums = antiStreakSums.toList(),
            dailyStreakCounts = streakCounts.toList(),
            dailyAntiStreakCounts = antiStreakCounts.toList()
        )
    }

    private fun emptySeries() = AppStatsRecordEngine.Series(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
    )

    // ── Episode-state persistence ──────────────────────────────────────────

    private fun loadStates(context: Context): Map<String, AppStatsRecordEngine.MetricState> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_EPISODES, emptySet()) ?: emptySet()
        return raw.associateWith { AppStatsRecordEngine.MetricState(episodeNotified = true) }
    }

    private fun saveStates(context: Context, states: Map<String, AppStatsRecordEngine.MetricState>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = states.filterValues { it.episodeNotified }.keys
        prefs.edit().putStringSet(KEY_EPISODES, active).apply()
    }
}

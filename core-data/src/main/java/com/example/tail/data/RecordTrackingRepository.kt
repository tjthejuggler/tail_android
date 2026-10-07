package com.example.tail.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Channel key for a habit's plain (non-subtyped) day-total record. */
const val RECORD_CHANNEL_TOTAL = "_total"

/**
 * Record tiers, evaluated per channel. ALL_TIME is the best single DAY
 * TOTAL ever; the rolling tiers are best rolling-window SUMS over any
 * consecutive [n]-day window — true rolling periods, not calendar ones.
 * BEST_SET is the best single INPUT ("set") ever — the amount logged in
 * one increment event, independent of the day total.
 */
enum class RecordTier {
    /** Best single input (set) ever logged for the channel. */
    BEST_SET,
    /** Best single day total ever (per channel). */
    ALL_TIME,
    /** Best sum over any 30 consecutive days (window ending before the evaluated day). */
    ROLLING_30D,
    /** Best sum over any 365 consecutive days (window ending before the evaluated day). */
    ROLLING_365D
}

/** One stored record: the best value, the date it was set, and whether it was back-filled from history. */
data class RecordEntry(
    val value: Int = 0,
    /** "YYYY-MM-DD" the record was set (null when zero / unknown). */
    val date: String? = null,
    /**
     * True when this entry was seeded by scanning the historical backlog
     * rather than recorded from a live beat. A same-day beat only
     * suppresses the flash when the previous record was NOT a seed — so
     * multiple inputs on the day a record falls flash once, while a
     * back-filled record dated today still lets the next input flash.
     */
    val seeded: Boolean = false
)

/** One tier record that an input just broke. */
data class RecordTierBeat(
    val tier: RecordTier,
    /** The standing record before the input (0 = none). */
    val oldValue: Int,
    /** The new record value (the evaluated day total / window sum). */
    val newValue: Int
)

/** One "new record" popup event: every tier record a single input broke for one channel. */
data class RecordFlashEvent(
    val habitName: String,
    /** Display label of the broken channel: the subtype (display) name, an exercise name, or the habit name. */
    val channelLabel: String,
    /** True when the record belongs to a subtype option of a subtyped habit. */
    val isSubtype: Boolean,
    val beats: List<RecordTierBeat>
)

/**
 * Day-total records per "channel" of a habit, powering the per-habit
 * "New record" popups (see [AppSettings.recordNotifHabits]).
 *
 * A channel is a subtype option of a subtyped habit, a weights exercise
 * (keyed via [WeightsRecordsRepository.recordKey]), or the habit itself
 * ([RECORD_CHANNEL_TOTAL]). Records are judged on DAY TOTALS: an input is
 * merged into the evaluated day's total before comparison, so several
 * small inputs across a day can jointly set a record.
 *
 * Standing records live here (with the date they were set, for same-day
 * suppression); the full historical day-total series used for SEEDING and
 * for rolling-window candidate computation is derived from the primary
 * stores (subtype store, habits DB) at evaluation time — the backlog is
 * the source of truth, this file only remembers what the engine has
 * already announced.
 *
 * File: `files/record_day_records.json`
 * Format:
 * ```json
 * {
 *   "Pullups": {
 *     "chinups": {
 *       "ALL_TIME":    { "value": 12, "date": "2026-09-01", "seeded": true },
 *       "ROLLING_30D": { "value": 40, "date": "2026-09-20", "seeded": true },
 *       "ROLLING_365D": { "value": 300, "date": "2026-10-01", "seeded": false }
 *     }
 *   }
 * }
 * ```
 *
 * Concurrency: every read-modify-write cycle is serialised by a PROCESS-WIDE
 * mutex in the companion object, because callers each construct their own
 * repository instance — the same pattern as [SubtypeDataRepository].
 */
class RecordTrackingRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType =
        object : TypeToken<MutableMap<String, MutableMap<String, MutableMap<String, RecordEntry>>>>() {}.type

    private val file: File get() = File(context.filesDir, FILE_NAME)

    companion object {
        private const val TAG = "RecordTrackingRepo"

        /** Versioned filename (v1 stored a different shape). */
        const val FILE_NAME = "record_day_records.json"

        /** Process-wide mutex serialising all read-modify-write cycles. */
        private val fileMutex = Mutex()
    }

    // ── Internal store operations ────────────────────────────────────────────

    /** Loads the full store: habit → channel → tier name → entry. */
    suspend fun loadAll(): Map<String, Map<String, Map<String, RecordEntry>>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext emptyMap()
                val text = file.readText()
                if (text.isBlank()) return@withContext emptyMap()
                gson.fromJson(text, mapType) ?: emptyMap()
            } catch (e: Exception) {
                Log.w(TAG, "loadAll failed: ${e.message}")
                emptyMap()
            }
        }

    /** The stored tier records for one channel (absent tiers = never seeded). */
    suspend fun entriesFor(habitName: String, channel: String): Map<RecordTier, RecordEntry> {
        val raw = loadAll()[habitName]?.get(channel) ?: return emptyMap()
        return raw.mapNotNull { (tierName, entry) ->
            val tier = try { RecordTier.valueOf(tierName) } catch (_: Exception) { return@mapNotNull null }
            tier to entry
        }.toMap()
    }

    /** Stores one tier record for a channel. */
    suspend fun put(habitName: String, channel: String, tier: RecordTier, entry: RecordEntry) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val habit = all[habitName]?.toMutableMap() ?: mutableMapOf()
            val channelMap = habit[channel]?.toMutableMap() ?: mutableMapOf()
            channelMap[tier.name] = entry
            habit[channel] = channelMap
            all[habitName] = habit
            withContext(Dispatchers.IO) {
                try {
                    file.writeText(prettyGson.toJson(all))
                } catch (e: Exception) {
                    Log.w(TAG, "save failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Moves all record data from [oldName] to [newName] so it survives a
     * habit rename. No-op if [oldName] has no stored data.
     */
    suspend fun renameHabit(oldName: String, newName: String) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val data = all.remove(oldName) ?: return@withLock
            all[newName] = data
            withContext(Dispatchers.IO) {
                try {
                    file.writeText(prettyGson.toJson(all))
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Removes all record data for a deleted habit. */
    suspend fun clearHabit(habitName: String) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            if (all.remove(habitName) != null) {
                withContext(Dispatchers.IO) {
                    try {
                        file.writeText(prettyGson.toJson(all))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}

// ── Pure day-series math (no Android dependencies) ──────────────────────────

/**
 * Best single-day total in [daily], excluding [excludeDate] when non-null.
 * Returns (value, date) — (0, null) when the series is empty.
 */
fun recordMaxDayTotal(
    daily: Map<String, Int>,
    excludeDate: String? = null
): Pair<Int, String?> {
    var best = 0
    var bestDate: String? = null
    for ((d, v) in daily) {
        if (d == excludeDate) continue
        if (v > best) {
            best = v
            bestDate = d
        }
    }
    return best to bestDate
}

/**
 * Sum of [daily] over the [windowDays]-day window ENDING on [end] (inclusive
 * both ends). Days missing from [daily] count as 0. [todayOverride] replaces
 * the end day's value — used when the evaluated day's total is not yet in
 * [daily] (the input being judged is still pre-write).
 */
fun recordWindowSumEnding(
    daily: Map<String, Int>,
    end: LocalDate,
    windowDays: Int,
    todayOverride: Int? = null
): Int {
    val start = end.minusDays((windowDays - 1).toLong())
    var sum = 0
    for ((d, v) in daily) {
        val ld = try { LocalDate.parse(d) } catch (_: Exception) { continue }
        if (!ld.isBefore(start) && !ld.isAfter(end)) sum += v
    }
    if (todayOverride != null && !end.isBefore(start)) sum += todayOverride
    return sum
}

/**
 * Best window sum over all windows of [windowDays] days ENDING strictly
 * before [endBefore]. Returns (sum, end-date of the best window).
 */
fun recordMaxWindowSum(
    daily: Map<String, Int>,
    windowDays: Int,
    endBefore: LocalDate
): Pair<Int, String?> {
    var best = 0
    var bestDate: String? = null
    for ((d, _) in daily) {
        val ld = try { LocalDate.parse(d) } catch (_: Exception) { continue }
        if (!ld.isBefore(endBefore)) continue
        val s = recordWindowSumEnding(daily, ld, windowDays)
        if (s > best) {
            best = s
            bestDate = d
        }
    }
    return best to bestDate
}

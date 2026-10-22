package com.example.tail.widget

import android.content.Context
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.health.GarminRepository
import com.example.tail.data.health.GarminType
import com.example.tail.data.health.SleepDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * ♟ Chess Readiness V4 — signal collection (the Green Light inputs).
 *
 * Gathers the mission-validated state signals from the phone's own data
 * sources. EVERY source is optional: a missing/unreadable source simply
 * yields null and the engine omits that signal (missing data NEVER adds
 * concern — this is the green-by-default guarantee).
 *
 *  - Prior-night AWAKE MIN: the wake half of the sleep-suite habit
 *    (logged at this morning's wake-up, describing LAST night — the
 *    self-logged twin of TailCue's "Garmin: Awake min"). Fallback: the
 *    previous morning's record.
 *  - HRV vs 7d baseline: cached Garmin proxy values
 *    ([GarminType.HRV_LAST_NIGHT] vs [GarminType.HRV_WEEKLY_AVG], %).
 *  - COFFEE today: any habit whose name is "Coffee" (case-insensitive)
 *    with a count > 0 today.
 *  - YESTERDAY'S BLITZ: rated Blitz games in the permanent chess log
 *    ([ChessReadinessLogStore], fed by the chess.com archive poll + the
 *    post-game share loop).
 *  - HOUR: wall clock (time-of-day is a first-class input).
 */
object ChessReadinessV4Signals {

    /** Habit-name match for the coffee predictor (mission #93762). */
    private const val COFFEE_HABIT = "coffee"

    /**
     * Collects all inputs for [ChessReadinessV4Engine.assess]. Never throws
     * — a failing source degrades to "no signal".
     */
    suspend fun collect(context: Context): ChessReadinessV4Engine.V4Inputs =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val today = LocalDate.now().toString()
            val yesterday = LocalDate.now().minusDays(1).toString()
            val hour = ZonedDateTime.now().hour

            val settings = try {
                SettingsRepository(context).settingsFlow.first()
            } catch (_: Exception) {
                null
            }

            // 1. Prior-night awake minutes (wake half of the sleep suite).
            val awakeMin = try {
                val sleepRepo = SleepDataRepository(context)
                val all = sleepRepo.loadAll()
                val wakeRecords = all.filterKeys { name ->
                    name.contains("wake", ignoreCase = true)
                }.ifEmpty { all }
                wakeRecords.values.asSequence()
                    .mapNotNull { it[today]?.awakeMin ?: it[yesterday]?.awakeMin }
                    .filter { it > 0 }
                    .maxOrNull()
            } catch (_: Exception) {
                null
            }

            // 2. HRV vs 7-day baseline (%) from the cached Garmin data.
            val hrvPct = try {
                val repo = GarminRepository(context)
                val last = repo.cachedDailyValue(GarminType.HRV_LAST_NIGHT, yesterday)
                    ?: repo.cachedDailyValue(GarminType.HRV_LAST_NIGHT, today)
                val avg = repo.cachedDailyValue(GarminType.HRV_WEEKLY_AVG, yesterday)
                    ?: repo.cachedDailyValue(GarminType.HRV_WEEKLY_AVG, today)
                if (last != null && avg != null && avg > 0)
                    (last - avg) * 100.0 / avg else null
            } catch (_: Exception) {
                null
            }

            // 3. Coffee logged today (any coffee-like habit with a count).
            val coffee = try {
                val uri = settings?.fileUri?.takeIf { it.isNotBlank() } ?: ""
                if (uri.isNotEmpty()) {
                    val db = HabitsRepository().loadDatabase(android.net.Uri.parse(uri), context)
                    db.entries.firstOrNull { it.key.trim().equals(COFFEE_HABIT, true) }
                        ?.value?.get(today)?.let { it > 0 } ?: false
                } else false
            } catch (_: Exception) {
                false
            }

            // 4. Rated Blitz games yesterday, from the permanent chess log
            //    (chess.com archive poll + post-game share loop feed it).
            val blitzYesterday = try {
                val games = ChessReadinessLogStore.loadGames(context)
                val yStart = LocalDate.parse(yesterday)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val yEnd = yStart + 24L * 60 * 60 * 1000
                games.count {
                    it.endTimeMs in yStart until yEnd &&
                        it.type.equals("BLITZ", ignoreCase = true) && it.rated
                }
            } catch (_: Exception) {
                null
            }

            // Category: the selected chess type (Blitz enables the
            // Blitz-only signals; the spec's default category is Blitz).
            val category = try {
                ChessReadinessV3Store.selectedVariant(context).let {
                    when (it.lowercase()) {
                        "blitz" -> "Blitz"
                        "bullet" -> "Bullet"
                        "rapid" -> "Rapid"
                        else -> "Chess960"
                    }
                }
            } catch (_: Exception) {
                "Blitz"
            }

            ChessReadinessV4Engine.V4Inputs(
                awakeMinLastNight = awakeMin,
                hour = hour,
                coffeeToday = coffee,
                yesterdayBlitzGames = blitzYesterday,
                hrvVsBaselinePct = hrvPct,
                category = category
            )
        }
    
        /**
         * Dev#38: the last 10 rated Blitz results ACROSS days (newest last) —
         * the rolling first-10 equivalent for the micro-session era where Blitz
         * days hold only 1–2 games.
         */
        fun blitzRolling10(context: Context): List<Boolean> = try {
            ChessReadinessLogStore.loadGames(context)
                .filter { it.rated && it.type.equals("BLITZ", ignoreCase = true) }
                .sortedBy { it.endTimeMs }
                .takeLast(10)
                .map { it.won }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Today's rated games for the in-session stop-rule watch, tagged with
     * their time-control category (win/not-win — the phone log does not
     * separate draws). Ordered by end time. Empty when no games exist today
     * (the watch then returns null → no warnings).
     */
    fun todayGames(context: Context): List<ChessReadinessV4Engine.SessionGame> = try {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(zone).toLocalDate().toString()
        ChessReadinessLogStore.loadGames(context)
            .filter { ChessReadinessV4Engine.dayKey(it.endTimeMs, zone) == today && it.rated }
            .sortedBy { it.endTimeMs }
            .map {
                ChessReadinessV4Engine.SessionGame(
                    category = when (it.type.lowercase()) {
                        "blitz" -> "Blitz"
                        "bullet" -> "Bullet"
                        "rapid" -> "Rapid"
                        else -> "Chess960"
                    },
                    won = it.won
                )
            }
    } catch (_: Exception) {
        emptyList()
    }
}
